#!/usr/bin/env node
/**
 * knowledge-sync CLI
 *
 *   sources    打印登记表摘要
 *   fetch      抓取到期/指定来源，只记录哈希与元数据（快照落在 gitignore 的 cache/）
 *   diff       对比 state.json，生成差异报告（只报位置与长度，不复制原文）
 *   proposals  为新增/变更来源生成改写工作单
 *   verify     离线复算生产检索算法，预测语料改动对召回的影响（不联网）
 *
 * 这个工具永远不写 backend/src/main/resources/knowledge/。入库必须是独立、经批准的手工提交。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { CACHE_DIR, REPORTS_DIR, ROOT, TOOL_DIR, autoSources, loadRegistry, loadState, saveState } from './lib/registry.mjs';
import { checkCitations, collectCitations, loadRules, rulesFileExists } from './lib/citations.mjs';
import { fetchSource, firstDifference } from './lib/http.mjs';
import { htmlToText, shorten } from './lib/extract.mjs';
import { writeWorksheets } from './lib/proposal.mjs';
import {
  DEFAULT_MIN_SCORE, MEDICAL_TERMS, OOV_GUARD_QUERIES, PINNED_CHECKS, guardOutOfVocabulary,
  loadCorpus, retrieve, vocabularyCoverage,
} from './lib/verify.mjs';

const [, , command = 'verify', ...rest] = process.argv;
const flags = new Set(rest.filter((item) => item.startsWith('--')));
const positional = rest.filter((item) => !item.startsWith('--'));
const dryRun = flags.has('--dry-run');

function parseArgs() {
  const only = [];
  let cycleOnly = false;
  for (const item of rest) {
    if (item === '--cycle') cycleOnly = true;
    else if (!item.startsWith('--')) only.push(item);
  }
  return { only, cycleOnly };
}

function stamp() {
  return new Date().toISOString().slice(0, 10);
}

async function commandSources() {
  const registry = loadRegistry();
  const counts = new Map();
  for (const source of registry.sources) {
    const key = `${source.priority}/${source.fetchMode}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  console.log(`登记来源 ${registry.sources.length} 条；词表 ${MEDICAL_TERMS.length} 个词；默认词法阈值 ${DEFAULT_MIN_SCORE}`);
  for (const [key, value] of [...counts.entries()].sort()) {
    console.log(`  ${key}: ${value}`);
  }
  console.log('');
  for (const source of registry.sources) {
    const flag = source.url ? (source.status === 'verified' ? '✓' : '?') : '·';
    console.log(
      `${flag} [${source.priority}] ${source.id.padEnd(30)} ${source.fetchMode.padEnd(6)} ${source.url || '（无 URL）'}`,
    );
  }
  console.log('\n图例：✓ 已核实 URL ·? 待核实 · 无 URL（须人工定位，否则删除）');
  console.log('RAG 语料只接受官方机构/公立医院公开资料，且必须改写 + 标注出处 + 未经临床审核 + owner 批准。');
}

async function commandFetch() {
  const { only, cycleOnly } = parseArgs();
  const registry = loadRegistry();
  const state = loadState();
  const now = Date.now();
  const due = registry.sources.filter((source) => {
    if (only.length > 0) return only.includes(source.id);
    if (cycleOnly) {
      const last = state.sources[source.id]?.fetchedAt;
      const cycle = (source.reviewCycleDays ?? 90) * 86400000;
      return !last || now - Date.parse(last) > cycle;
    }
    return true;
  });
  const targets = due.filter((source) => source.fetchMode === 'auto');
  const skipped = due.filter((source) => source.fetchMode !== 'auto');
  console.log(`抓取目标 ${targets.length} 条；跳过 ${skipped.length} 条 manual（需人工阅读）`);
  for (const source of skipped) console.log(`  跳过 ${source.id}：${source.notes ?? 'manual 模式'}`);
  mkdirSync(CACHE_DIR, { recursive: true });

  const records = {};
  for (const source of targets) {
    process.stdout.write(`  ${source.id.padEnd(30)} `);
    const result = await fetchSource(source, state.sources[source.id]);
    records[source.id] = result;
    const detail = result.status === 'unchanged'
      ? `304 未变更${result.httpStatus ? `（HTTP ${result.httpStatus}）` : ''}`
      : `${result.status}${result.httpStatus ? ` HTTP ${result.httpStatus}` : ''}`
        + `${result.sha256 ? ` sha=${result.sha256.slice(0, 12)}… ${result.bytes}B` : ''}`;
    console.log(`${detail}${result.note ? ` — ${result.note}` : ''}`);
  }

  if (dryRun) {
    console.log('\n--dry-run：未写 state.json');
    return;
  }
  for (const [id, result] of Object.entries(records)) {
    const previous = state.sources[id] ?? {};
    if (result.sha256 == null) {
      // 抓取失败或未取到正文：只更新尝试时间与状态，绝不抹掉上一次成功的哈希与体积。
      state.sources[id] = {
        ...previous,
        ...result,
        sha256: previous.sha256 ?? null,
        bytes: previous.bytes ?? null,
      };
    } else {
      state.sources[id] = { ...previous, ...result };
    }
  }
  saveState(state);
  console.log(`\n已写 state.json（${Object.keys(records).length} 条）。原文快照在 cache/，已被 git 忽略。`);
}

async function commandDiff() {
  const state = loadState();
  const registry = loadRegistry();
  mkdirSync(REPORTS_DIR, { recursive: true });
  const lines = [
    `# 知识源同步报告 ${stamp()}`,
    '',
    '> 报告只记录状态、哈希与差异位置，不复制任何原文。快照在 gitignore 的 `cache/`。',
    '',
    '| 来源 | 状态 | HTTP | sha256 | 字节 | 首次差异位置 | 备注 |',
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  let changes = 0;
  for (const source of registry.sources) {
    const record = state.sources[source.id];
    if (!record) {
      lines.push(`| ${source.id} | 未抓取 | - | - | - | - | ${source.fetchMode === 'auto' ? '下次 fetch 覆盖' : 'manual，需人工阅读'} |`);
      continue;
    }
    let where = '-';
    if (record.status === 'updated') {
      const previousPath = join(CACHE_DIR, source.id, 'previous.html');
      if (existsSync(previousPath)) {
        const before = htmlToText(readFileSync(previousPath, 'utf8'));
        const after = htmlToText(readFileSync(join(CACHE_DIR, source.id, 'snapshot.html'), 'utf8'));
        const delta = firstDifference(before, after);
        where = `第 ${delta.offset} 字（${delta.previousLength} → ${delta.nextLength} 字）`;
      } else {
        where = '无旧快照，需人工比对';
      }
      changes += 1;
    } else if (record.status === 'unreachable' || record.status === 'http-error' || record.status === 'blocked-by-waf') {
      changes += 1;
    }
    lines.push(
      `| ${source.id} | ${record.status} | ${record.httpStatus ?? '-'} | ${(record.sha256 ?? '-').slice(0, 16)} |`
      + ` ${record.bytes ?? '-'} | ${where} | ${shorten(record.note ?? '', 60)} |`,
    );
  }
  lines.push('', `本次变更 ${changes} 条。下一步：对 new/updated 来源运行 \`npm run proposals\`，人工改写后再提语料 commit。`, '');
  const path = join(REPORTS_DIR, `sync-${stamp()}.md`);
  writeFileSync(path, lines.join('\n'), 'utf8');
  console.log(`差异报告：${path}`);
  console.log(`变更 ${changes} 条。原始快照与本报告都不入库（reports/ 已 git 忽略）。`);
  console.log('下一步：对 new/updated 来源运行 `npm run proposals`，人工改写后再提语料 commit。');
  console.log('旧快照保留在 cache/<id>/previous.html，差异位置已在上表给出；原文不入库。');
}

async function commandProposals() {
  const state = loadState();
  const registry = loadRegistry();
  const candidates = registry.sources.filter((source) => {
    const record = state.sources[source.id];
    return record && record.status !== 'unchanged' && record.status !== 'new';
  });
  const fresh = registry.sources.filter((source) => {
    const record = state.sources[source.id];
    return record?.status === 'new' || (!record && source.fetchMode === 'auto');
  });
  const written = writeWorksheets([...fresh, ...candidates], state.sources);
  console.log(`生成改写工作单 ${written.length} 份 → reports/proposals/`);
  for (const path of written) console.log(`  ${path}`);
  if (written.length === 0) console.log('  （没有 new/updated 来源；先跑 npm run fetch）');
}

function commandVerify() {
  const documents = loadCorpus();
  const chunkTotal = documents.reduce((sum, document) => sum + document.chunks.length, 0);
  console.log(`语料：${documents.length} 个文件，${chunkTotal} 个可检索片段，词表 ${MEDICAL_TERMS.length} 词，默认阈值 ${DEFAULT_MIN_SCORE}`);
  console.log('');
  console.log('| 文件 | 状态 | 段落 | 最长段落 | 片段 | 主题词命中词表 |');
  console.log('| --- | --- | --- | --- | --- | --- |');
  for (const document of documents) {
    const coverage = vocabularyCoverage(document);
    const note = coverage.outside.length === 0
      ? `${coverage.matched.length}/${coverage.topicTokens.length}`
      : `${coverage.matched.length}/${coverage.topicTokens.length}（词表外：${coverage.outside.join('、')}）`;
    console.log(`| ${document.file} | ${document.status} | ${document.sectionCount} | ${document.longestSection} |`
      + ` ${document.chunks.length} | ${note} |`);
  }

  const problems = [];
  for (const document of documents) {
    if (!document.approved) {
      problems.push(`${document.file}：来源不是 https:// 开头，按 InMemoryKnowledgeCatalog.java:77 该文档不会对患者可见。`);
    }
    if (document.longestSection > 420) {
      problems.push(`${document.file}：最长段落 ${document.longestSection} 字，超过 CHUNK_SIZE，会被切成多块并产生重叠片段。`);
    }
  }

  console.log('');
  let failures = 0;
  for (const check of PINNED_CHECKS) {
    const result = retrieve(documents, check.query, check.maxResults);
    const ok = check.expect(result);
    if (!ok) failures += 1;
    const tie = result.evidence.length > 1
      && Math.abs(result.evidence[0].score - result.evidence[1].score) < 0.001;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${check.id} — 查询「${check.query}」 ${check.detail(result)}`);
    console.log(`     锁定来源：${check.pinnedBy}`);
    if (tie) {
      console.log('     警告：top1 与 top2 分数并列，生产环境顺序由 ConcurrentHashMap 迭代决定，可能翻转。');
      problems.push(`${check.id}：top1/top2 分数并列（${result.evidence[0].score}），生产环境 top-1 可能翻转。`);
    }
  }

  console.log('');
  const guard = guardOutOfVocabulary(documents);
  const void_ = guard.filter((item) => !item.outsideVocabulary);
  const grounded = guard.filter((item) => item.outsideVocabulary && item.grounded);
  console.log(`词表外守卫：${guard.length} 条查询，词表外 ${guard.length - void_.length} 条，命中 ${grounded.length} 条`);
  console.log('  机制：termScore=0 时 score = 0.30×bigram余弦 + 0.08（https 来源），过 0.28 需余弦 ≥ 0.667。');
  for (const item of guard) {
    const state = !item.outsideVocabulary ? '用例失效' : item.grounded ? '已命中' : '未命中';
    const detail = item.grounded ? ` top1=${item.top.title} ${item.top.score}` : '';
    const terms = item.outsideVocabulary ? '' : ` queryTerms=${JSON.stringify(item.queryTerms)}`;
    console.log(`  ${state.padEnd(6)} 「${item.query}」${terms}${detail}`);
  }
  if (void_.length > 0) {
    failures += void_.length;
    console.log(`\n警告：${void_.length} 条守卫查询已被词表收编，这些用例不再测任何东西，需要换查询。`);
  }
  if (grounded.length > 0) {
    failures += grounded.length;
    problems.push(`词表外查询开始命中（${grounded.map((item) => item.query).join('、')}）：阈值、语料或词表三者之一发生了变化，需要显式裁定——这不是 bug，也不是可以直接忽略的信号。`);
  }
  console.log('  提醒：本守卫只覆盖词法路径。启用 embedding 后 grounded 可由向量余弦单独产生（HybridKnowledgeCatalog.java:43），本工具不验证该路径。');

  console.log('');
  const notApproved = documents.filter((document) => !document.approved);
  const outOfVocabulary = documents.filter((document) => vocabularyCoverage(document).outside.length > 0);
  console.log(`不可见文档（来源非 https）：${notApproved.length}`);
  console.log(`主题含词表外术语（词法得分受损，只能靠 bigram 0.30 权重）：${outOfVocabulary.length}`);
  if (problems.length > 0) {
    console.log('\n问题：');
    for (const problem of problems) console.log(`  - ${problem}`);
  }
  console.log(`\n结论：${failures === 0 && problems.length === 0 ? '语料契约与被锁定的断言一致。' : '存在需处理的问题，见上。'}`);
  process.exitCode = failures > 0 ? 1 : 0;
}

async function commandRefs() {
  const commit = positional.find((item) => !item.startsWith('--')) ?? 'HEAD';
  const data = JSON.parse(readFileSync(join(TOOL_DIR, 'doc-refs.json'), 'utf8'));
  console.log(`校验 ${data.refs.length} 条文档引用（提交态：${commit}）`);
  let failed = 0;
  for (const ref of data.refs) {
    let text;
    try {
      text = execFileSync('git', ['show', `${commit}:${ref.path}`], {
        cwd: ROOT, encoding: 'utf8', maxBuffer: 1e8,
      });
    } catch {
      console.log(`FAIL ${ref.path} —— 该提交态下取不到此文件`);
      failed += 1;
      continue;
    }
    const line = text.split('\n')[ref.line - 1] ?? '';
    if (line.includes(ref.mustContain)) {
      const note = ref.note ? `  // ${ref.note}` : '';
      console.log(`PASS ${ref.path.split('/').pop()}:${ref.line} ${JSON.stringify(ref.mustContain)}${note}`);
    } else {
      console.log(`FAIL ${ref.path.split('/').pop()}:${ref.line} 期望含 ${JSON.stringify(ref.mustContain)}`
        + ` 实际 ${JSON.stringify(line.trim().slice(0, 70))}  [${ref.doc}]`);
      failed += 1;
    }
  }
  console.log(`\n${failed === 0 ? '全部引用与提交态一致。' : `${failed} 条失效——先按 mustContain 内容串重新定位，再更新 doc-refs.json 的行号与 baseline。`}`);
  process.exitCode = failed > 0 ? 1 : 0;
}

function commandCitations() {
  if (!rulesFileExists()) {
    console.log('backend/src/main/resources/safety-rules.json 不存在——规则尚未数据化，本检查不适用。');
    return;
  }
  const rules = loadRules();
  const registry = loadRegistry();
  const rows = collectCitations(rules);
  const findings = checkCitations(rules, registry);
  const ruleCount = (rules.rules ?? []).length;
  const comboCount = (rules.combinations ?? []).length;
  console.log(`规则文件：${ruleCount} 条规则 + ${comboCount} 条组合 = ${ruleCount + comboCount} 个声明；引用 ${rows.length} 条，去重后 ${new Set(rows.map((row) => row.code + '::' + row.id)).size} 条`);
  const codes = new Set((rules.rules ?? []).map((rule) => rule.code));
  for (const combination of rules.combinations ?? []) codes.add(combination.code);
  console.log(`涉及规则码 ${codes.size} 个`);
  console.log('');
  if (findings.length === 0) {
    console.log('全部引用的 id 都能在 sources.json 找到，且无占位条目。');
    return;
  }
  let errors = 0;
  for (const finding of findings) {
    if (finding.level === 'error') errors += 1;
    console.log(`${finding.level === 'error' ? 'ERROR' : 'WARN '} ${finding.code.padEnd(22)} ${finding.id.padEnd(34)} ${finding.why}`);
  }
  console.log(`\n${errors === 0 ? '无断链，但有需人工确认的引用。' : `${errors} 条断链或不可用引用——出处不可追溯等于没有出处。`}`);
  console.log('提示：本检查只读，不修改规则文件。修正 id 或补登记由规则文件 owner 决定。');
  process.exitCode = errors > 0 ? 1 : 0;
}

const commands = {
  sources: commandSources,
  fetch: commandFetch,
  diff: commandDiff,
  proposals: commandProposals,
  verify: commandVerify,
  refs: commandRefs,
  citations: commandCitations,
};

const selected = commands[command];
if (!selected) {
  console.error(`未知命令：${command}`);
  console.error('可用：sources | fetch | diff | proposals | verify | refs | citations');
  process.exit(2);
}
await selected();