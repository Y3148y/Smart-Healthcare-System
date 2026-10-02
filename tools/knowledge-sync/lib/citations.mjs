/**
 * Cross-file citation checker.
 *
 * Rules carry provenance in two places: the rule data file (`backend/src/main/resources/safety-rules.json`)
 * and the source registry (`tools/knowledge-sync/sources.json`). If a citation id in the rule file
 * has no matching entry in the registry, the provenance is dangling — a reader following the id
 * finds nothing, and the claim looks sourced when it is not.
 *
 * This checker is read-only with respect to both files. It never edits the rule file; it only
 * reports, so the rule-file owner keeps single-writer ownership of their own data.
 *
 * It also flags two failure modes that are easy to miss by eye:
 *   - ids that only exist in the registry but carry a placeholder URL or `toVerify` status;
 *   - ids registered as `manual` (cannot be fetched by tooling, so nobody will notice drift).
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadRegistry } from './registry.mjs';

export const RULES_PATH = join(ROOT, 'backend', 'src', 'main', 'resources', 'safety-rules.json');

export function collectCitations(rulesJson) {
  const rows = [];
  const push = (code, citations) => {
    for (const citation of citations ?? []) rows.push({ code, id: citation.id, note: citation.note ?? '' });
  };
  for (const rule of rulesJson.rules ?? []) push(rule.code, rule.citations);
  for (const combination of rulesJson.combinations ?? []) push(combination.code, combination.citations);
  return rows;
}

export function checkCitations(rulesJson, registry) {
  const byId = new Map(registry.sources.map((source) => [source.id, source]));
  const grouped = new Map();
  for (const row of collectCitations(rulesJson)) {
    const key = `${row.code}::${row.id}`;
    if (!grouped.has(key)) grouped.set(key, { level: 'ok', code: row.code, id: row.id, reasons: [] });
  }
  for (const entry of grouped.values()) {
    const source = byId.get(entry.id);
    if (!source) {
      entry.level = 'error';
      entry.reasons.push('该 id 在 sources.json 中不存在，引用断链');
      continue;
    }
    if (!source.url) {
      entry.level = 'error';
      entry.reasons.push('登记条目没有 URL，按收录政策应删除而不是被引用');
    } else if (source.status !== 'verified') {
      entry.level = entry.level === 'error' ? entry.level : 'warn';
      entry.reasons.push(`来源状态为 ${source.status}，尚未核实`);
    }
    if (source.fetchMode === 'manual') {
      entry.level = entry.level === 'error' ? entry.level : 'warn';
      entry.reasons.push('manual 抓取，无法自动发现内容漂移');
    }
    if (/占位/.test(source.notes ?? '') || /占位/.test(source.name ?? '')) {
      entry.level = 'error';
      entry.reasons.push('来源自述为占位条目，不得作为正式引用');
    }
  }
  return [...grouped.values()]
    .filter((entry) => entry.level !== 'ok')
    .map((entry) => ({ level: entry.level, code: entry.code, id: entry.id, why: entry.reasons.join('；') }));
}

export function rulesFileExists() {
  return existsSync(RULES_PATH);
}

export function loadRules() {
  return JSON.parse(readFileSync(RULES_PATH, 'utf8'));
}