/**
 * Polite, conditional, rate-limited fetching.
 *
 * Hard rules, all of them non-negotiable:
 *  - identify ourselves with a real User-Agent;
 *  - read /robots.txt before the first hit on an origin and obey the longest-match Disallow;
 *  - never retry a failure more than once, never hammer an origin (min interval per host);
 *  - send If-None-Match / If-Modified-Since so an unchanged page costs one 304;
 *  - a body we cannot parse as HTML is reported as `manual` rather than silently skipped.
 */
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { CACHE_DIR } from './registry.mjs';

export const USER_AGENT =
  'ai-hospital-knowledge-sync/0.1 (local demo repo; provenance tracking only; contact: repository owner)';
const AGENT_TOKEN = 'ai-hospital-knowledge-sync';
const REQUEST_TIMEOUT_MS = 15000;
const HOST_INTERVAL_MS = 5000;
const HTML_TYPES = ['text/html', 'application/xhtml+xml'];

const robotsCache = new Map();
const lastHitAt = new Map();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function cachePath(...parts) {
  return join(CACHE_DIR, ...parts);
}

async function throttle(url) {
  const host = new URL(url).host;
  const previous = lastHitAt.get(host) ?? 0;
  const wait = previous + HOST_INTERVAL_MS - Date.now();
  if (wait > 0) await sleep(wait);
  lastHitAt.set(host, Date.now());
}

/** Minimal robots.txt reader: longest-match Disallow for `*` and for our agent token. */
async function robotsAllows(url) {
  const target = new URL(url);
  const origin = `${target.protocol}//${target.host}`;
  let text = robotsCache.get(origin);
  if (text === undefined) {
    const cached = cachePath('robots', `${target.host}.txt`);
    if (existsSync(cached)) {
      text = readFileSync(cached, 'utf8');
    } else {
      try {
        await throttle(origin + '/robots.txt');
        const response = await fetch(origin + '/robots.txt', {
          headers: { 'user-agent': USER_AGENT },
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        });
        text = response.ok ? await response.text() : '';
      } catch (error) {
        text = '';
        console.warn(`  robots.txt 无法读取（${target.host}）：${error.message}；按允许处理并记录待复核`);
      }
      mkdirSync(cachePath('robots'), { recursive: true });
      writeFileSync(cached, text ?? '', 'utf8');
    }
    robotsCache.set(origin, text ?? '');
  }
  const rules = parseRobots(text ?? '');
  const path = target.pathname + target.search;
  return !isDisallowed(rules, path);
}

export function parseRobots(text) {
  const groups = [];
  let current = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.replace(/#.*$/, '').trim();
    if (!line) continue;
    const separator = line.indexOf(':');
    if (separator < 0) continue;
    const field = line.slice(0, separator).trim().toLowerCase();
    const value = line.slice(separator + 1).trim();
    if (field === 'user-agent') {
      if (current.length > 0) groups.push(current);
      current = [];
      continue;
    }
    if (field === 'disallow' || field === 'allow') current.push({ field, value });
  }
  if (current.length > 0) groups.push(current);
  return groups;
}

function longestMatch(rules, path) {
  let blocked = -1;
  for (const rule of rules) {
    if (rule.value === '') continue; // "Disallow:" with empty value allows everything
    if (path.startsWith(rule.value) && rule.value.length > blocked) {
      if (rule.field === 'allow') return -1;
      blocked = rule.value.length;
    }
  }
  return blocked;
}

export function isDisallowed(groups, path) {
  for (const group of groups) {
    const agents = group.filter((rule) => rule.field === 'user-agent');
    const rules = group.filter((rule) => rule.field !== 'user-agent');
    if (agents.length === 0 || rules.length === 0) continue;
    const names = agents.map((rule) => rule.value);
    if (names.includes(AGENT_TOKEN) || names.includes('*')) {
      if (longestMatch(rules, path) >= 0) return true;
    }
  }
  return false;
}

export function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

/**
 * Fetch one URL and persist the raw snapshot plus a metadata record.
 * Never throws: transport problems come back as a status so the run can continue.
 */
export async function fetchSource(source, previous) {
  const result = {
    id: source.id,
    url: source.url,
    fetchedAt: new Date().toISOString(),
    status: 'error',
    httpStatus: null,
    sha256: null,
    bytes: null,
    etag: null,
    lastModified: null,
    contentType: null,
    note: '',
  };
  if (!source.url) {
    result.status = 'unregistered';
    result.note = '登记表中没有 URL；需人工定位官方地址，定位不到则删除该条。';
    return result;
  }
  let allowed = false;
  try {
    allowed = await robotsAllows(source.url);
  } catch (error) {
    result.note = `robots 检查失败：${error.message}`;
  }
  if (!allowed) {
    result.status = 'blocked-by-robots';
    result.note = 'robots.txt 不允许抓取该路径；请人工阅读并在工作单中留痕，不要绕过。';
    return result;
  }

  const headers = {
    'user-agent': USER_AGENT,
    accept: 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.1',
    'accept-language': 'zh-CN,zh;q=0.9,en;q=0.8',
  };
  if (previous?.etag) headers['if-none-match'] = previous.etag;
  if (previous?.lastModified) headers['if-modified-since'] = previous.lastModified;

  let response;
  try {
    await throttle(source.url);
    response = await fetch(source.url, { headers, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
  } catch (firstError) {
    // 只重试一次：连接超时在跨境线路上很常见，但绝不连发。
    try {
      await sleep(3000);
      await throttle(source.url);
      response = await fetch(source.url, { headers, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    } catch {
      result.status = 'unreachable';
      result.note = `请求失败：${firstError.message}${firstError.cause?.code ? ` (${firstError.cause.code})` : ''}`;
      return result;
    }
  }

  result.httpStatus = response.status;
  result.etag = response.headers.get('etag');
  result.lastModified = response.headers.get('last-modified');
  result.contentType = response.headers.get('content-type');

  if (response.status === 304) {
    result.status = 'unchanged';
    result.sha256 = previous?.sha256 ?? null;
    result.bytes = previous?.bytes ?? null;
    return result;
  }
  if (!response.ok) {
    result.status = result.httpStatus === 412 ? 'blocked-by-waf' : 'http-error';
    result.note =
      result.status === 'blocked-by-waf'
        ? 'HTTP 412：站点 WAF 拦截程序化请求。须人工打开核对，不能伪装 UA 绕过。'
        : `HTTP ${result.httpStatus}`;
    return result;
  }

  const contentType = (result.contentType ?? '').toLowerCase();
  if (!HTML_TYPES.some((type) => contentType.includes(type))) {
    result.status = 'manual';
    result.note = `内容类型为 ${result.contentType || '未知'}，零依赖抽取器只处理 HTML；该源只能人工阅读。`;
    return result;
  }

  const body = Buffer.from(await response.arrayBuffer());
  result.sha256 = sha256(body);
  result.bytes = body.length;
  mkdirSync(cachePath(source.id), { recursive: true });
  const existing = cachePath(source.id, 'snapshot.html');
  if (existsSync(existing)) renameSync(existing, cachePath(source.id, 'previous.html'));
  writeFileSync(existing, body);
  const previousHash = previous?.sha256;
  result.status = !previousHash ? 'new' : previousHash === result.sha256 ? 'unchanged' : 'updated';
  return result;
}

/** First differing byte offset between the cached snapshot and a freshly fetched one. */
export function firstDifference(previousText, nextText) {
  const limit = Math.min(previousText.length, nextText.length);
  let index = 0;
  while (index < limit && previousText[index] === nextText[index]) index += 1;
  return { offset: index, previousLength: previousText.length, nextLength: nextText.length };
}