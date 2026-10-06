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
  const byId = new Map();
  for (const source of registry.sources) {
    for (const id of [source.id, ...(source.aliases ?? [])]) {
      if (byId.has(id)) throw new Error(`Duplicate source ID or alias: ${id}`);
      byId.set(id, source);
    }
  }
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

/**
 * Structural invariants of the rule data file.
 *
 * These are deliberately semantic rather than positional. Line-number assertions do not fit a
 * JSON data file: any added key shifts every later line, and a `mustContain` probe can be
 * satisfied by an unrelated line that merely mentions the same word (measured on the real file —
 * the word "citations" occurs 17 times, and the first occurrence is inside `unreviewedDefault`,
 * not inside any citations field).
 *
 * Only invariants that cannot fire spuriously are asserted:
 *   1. a declaration with ZERO citations must set `citationGap` — otherwise it reads as sourced
 *      while having no source at all. (The converse is NOT an error: `citationGap` legitimately
 *      coexists with citations, meaning "partially covered".)
 *   2. a combination's code must exist among the rule codes — catches typos like ER-PREGNANCE-001.
 *   3. every `{ref:name}` resolves to a declared vocabulary key; a dangling ref expands to a
 *      literal that never matches, silently disabling a pattern.
 *   4. rule codes are unique within rules + derivedRules. Sharing a code with a combination is
 *      by design (one code, both a direct expression and a cross-clause variant).
 *   5. every derived rule states its `condition`, otherwise the derivation is invisible.
 */
export function checkRuleInvariants(rulesJson) {
  const problems = [];
  const directCodes = new Set();
  for (const entry of [...(rulesJson.rules ?? []), ...(rulesJson.derivedRules ?? [])]) {
    directCodes.add(entry.code);
  }

  const seen = new Map();
  for (const [group, entries] of [
    ['rules', rulesJson.rules ?? []],
    ['derivedRules', rulesJson.derivedRules ?? []],
  ]) {
    for (const entry of entries) {
      const citations = entry.citations ?? [];
      if (citations.length === 0 && entry.citationGap !== true) {
        problems.push({
          level: 'error',
          code: entry.code,
          why: `既无引用也没标 citationGap——读起来像有出处，实际没有（${group}）`,
        });
      }
      if (seen.has(entry.code)) {
        problems.push({ level: 'error', code: entry.code, why: `规则码重复：${seen.get(entry.code)} 与 ${group} 各声明一次` });
      } else {
        seen.set(entry.code, group);
      }
      if (group === 'derivedRules' && !entry.condition) {
        problems.push({ level: 'error', code: entry.code, why: '派生规则缺 condition，派生逻辑不可见' });
      }
    }
  }

  for (const combination of rulesJson.combinations ?? []) {
    if (!directCodes.has(combination.code)) {
      problems.push({
        level: 'error',
        code: combination.code,
        why: 'combinations 引用了一个不存在于 rules/derivedRules 的规则码（拼写错误或规则已删）',
      });
    }
  }

  const vocabulary = Object.keys(rulesJson.vocabulary ?? {});
  const raw = JSON.stringify(rulesJson);
  const refs = [...raw.matchAll(/\{ref:([a-zA-Z0-9_]+)\}/g)].map((match) => match[1]);
  for (const name of new Set(refs)) {
    if (!vocabulary.includes(name)) {
      problems.push({
        level: 'error',
        code: '(vocabulary)',
        why: `{ref:${name}} 指向不存在的词表键；展开后会变成永不匹配的字面量`,
      });
    }
  }

  // Auxiliary patterns are the third declaration group and used to be unchecked. A pattern that
  // carries neither citations, nor citationGap, nor a note explaining its non-clinical origin
  // reads as sourced when it is not — the exact failure this file exists to prevent.
  //
  // `UNCLEAR_BLEEDING_RULE` is a known legitimate exception: it exists because of a product
  // ruling (D11 "量级不明不升急症"), so it has no clinical source and no gap to declare. It is
  // accepted via its `note`, and that exception is stated here rather than left to chance.
  for (const pattern of rulesJson.auxiliaryPatterns ?? []) {
    const citations = pattern.citations ?? [];
    if (citations.length > 0) continue;
    if (pattern.citationGap === true) continue;
    if (typeof pattern.note === 'string' && pattern.note.trim() !== '') continue;
    problems.push({
      level: 'error',
      code: `(auxiliary) ${pattern.name ?? '(未命名)'}`,
      why: '既无引用、也没标 citationGap、没有 note 说明其非临床来源——读起来像有出处，实际没有',
    });
  }

  return { problems, codes: [...seen.keys()], refsUsed: [...new Set(refs)], vocabularyKeys: vocabulary };
}

export function rulesFileExists() {
  return existsSync(RULES_PATH);
}

export function loadRules() {
  return JSON.parse(readFileSync(RULES_PATH, 'utf8'));
}
