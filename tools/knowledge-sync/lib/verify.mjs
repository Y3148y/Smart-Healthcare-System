/**
 * Offline replay of the production retrieval algorithm.
 *
 * Why this exists: the bundled corpus is loaded by InMemoryKnowledgeCatalog, which decides both
 * whether a document is visible to patients at all and how it scores. Adding or editing a
 * knowledge file therefore changes live behaviour immediately. This module mirrors that code
 * 1:1 so we can predict the effect of a corpus change *before* it reaches a patient, without
 * starting the application or touching Java.
 *
 * Mirrored from:
 *   InMemoryKnowledgeCatalog.java:32-33   CHUNK_SIZE / CHUNK_OVERLAP
 *   InMemoryKnowledgeCatalog.java:73-84   add() —— source 必须以 https:// 开头才 READY 并建索引
 *   InMemoryKnowledgeCatalog.java:104-110 score() —— 0.62 词项 + 0.30 bigram 余弦 + 0.08/0.02 权威
 *   InMemoryKnowledgeCatalog.java:113-132 chunk() —— 420 字滑窗 + 60 字重叠 + 句末吸附
 *   InMemoryKnowledgeCatalog.java:134-156 medicalTerms() / ngrams() / cosine()
 *   InMemoryKnowledgeCatalog.java:161-179 loadBundledKnowledge() —— 行首解析与文件名排序
 *
 * Known divergences from the Java original (deliberate, documented):
 *   1. Java iterates chunks.values() of a ConcurrentHashMap, so the order of equal-scoring
 *      chunks is unspecified. We sort by (score desc, id asc) and report ties separately,
 *      because a tie means the production top-1 can flip between runs.
 *   2. Java's \s and JS's \s cover slightly different Unicode sets; this only matters for
 *      whitespace-heavy inputs and does not affect the bundled corpus.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CORPUS_DIR } from './registry.mjs';

export const CHUNK_SIZE = 420;
export const CHUNK_OVERLAP = 60;
const SCORE_CAP = 0.99;
const WEIGHT_TERM = 0.62;
const WEIGHT_DENSE = 0.30;
const AUTHORITY_HTTPS = 0.08;
const AUTHORITY_OTHER = 0.02;
const RESULT_CAP = 8;
export const DEFAULT_MIN_SCORE = 0.28;

/** Verbatim copy of InMemoryKnowledgeCatalog.java:34-40. Change detection is the point. */
export const MEDICAL_TERMS = [
  '急诊', '红旗症状', '胸痛', '胸闷', '大汗', '冷汗', '呼吸困难', '意识障碍', '昏迷', '晕厥',
  '脑卒中', '中风', '口角歪斜', '肢体无力', '言语障碍', '咳嗽', '咳痰', '喘息', '哮喘',
  '腹痛', '反酸', '恶心', '呕吐', '腹泻', '便秘', '消化不良', '消化道出血', '呼吸内科', '消化内科',
  '头晕', '眩晕', '头痛', '神经内科', '耳鼻喉科', '骨折', '摔断', '摔伤', '嗓子疼', '咽痛',
  '喉咙痛', '痛经', '经期腹痛', '妇科', '骨科', '吞咽', '抽搐', '出血', '高热',
  '流鼻涕', '鼻塞', '打喷嚏', '鼻部症状',
];

const normalize = (value) => (value == null ? '' : String(value).replace('\r', ' ').trim());

/** Mirrors loadBundledKnowledge():161-176 — only these four line shapes are meaningful. */
export function parseKnowledgeMarkdown(markdown, fallbackTitle) {
  const lines = markdown.split(/\r\n|\r|\n/);
  const title = lines
    .filter((line) => line.startsWith('# '))
    .map((line) => line.slice(2).trim())
    .at(0) ?? fallbackTitle;
  const source = lines
    .filter((line) => line.startsWith('来源：') || line.startsWith('补充来源：'))
    .map((line) => line.slice(line.indexOf('：') + 1).trim())
    .join(' | ') || '官方公开资料';
  const topics = lines
    .filter((line) => line.startsWith('主题：'))
    .map((line) => line.slice(line.indexOf('：') + 1).trim())
    .at(0) ?? '';
  const body = lines
    .filter((line) => !line.startsWith('# ') && !line.startsWith('来源：')
      && !line.startsWith('补充来源：') && !line.startsWith('主题：'))
    .join('\n')
    .trim();
  return { title, source, topics, body: topics === '' ? body : `主题：${topics}\n${body}` };
}

function ngrams(text) {
  const compact = normalize(text).replace(/[\p{P}\p{S}\s]+/gu, '');
  const vector = new Map();
  for (let i = 0; i + 1 < compact.length; i += 1) {
    const bigram = compact.slice(i, i + 2);
    vector.set(bigram, (vector.get(bigram) ?? 0) + 1);
  }
  return vector;
}

function cosine(left, right) {
  if (left.size === 0 || right.size === 0) return 0;
  let dot = 0;
  let leftNorm = 0;
  let rightNorm = 0;
  for (const [term, weight] of left) {
    dot += weight * (right.get(term) ?? 0);
    leftNorm += weight * weight;
  }
  for (const weight of right.values()) rightNorm += weight * weight;
  if (leftNorm === 0 || rightNorm === 0) return 0;
  return dot / Math.sqrt(leftNorm * rightNorm);
}

function medicalTerms(text) {
  const terms = new Set();
  for (const term of MEDICAL_TERMS) if (text.includes(term)) terms.add(term);
  return terms;
}

function chunk(documentId, title, body, source) {
  const chunks = [];
  for (const section of body.split(/\n\s*\n|(?=^##\s)/, -1)) {
    const clean = normalize(section);
    if (clean.trim() === '') continue;
    let start = 0;
    while (start < clean.length) {
      let end = Math.min(clean.length, start + CHUNK_SIZE);
      if (end < clean.length) {
        const punctuation = Math.max(clean.lastIndexOf('。', end), clean.lastIndexOf('；', end));
        if (punctuation > start + 120) end = punctuation + 1;
      }
      const text = clean.slice(start, end).trim();
      if (text !== '') {
        chunks.push({
          id: `${documentId}-c${chunks.length + 1}`,
          title,
          source,
          text,
          vector: ngrams(title + text),
        });
      }
      if (end >= clean.length) break;
      start = Math.max(start + 1, end - CHUNK_OVERLAP);
    }
  }
  return chunks;
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

/** Load the corpus exactly the way the application does, including READY/PENDING behaviour. */
export function loadCorpus(dir = CORPUS_DIR) {
  const files = readdirSync(dir).filter((name) => name.endsWith('.md')).sort();
  const documents = [];
  for (const name of files) {
    const parsed = parseKnowledgeMarkdown(readFileSync(join(dir, name), 'utf8'), name);
    const approved = parsed.source.startsWith('https://');
    const chunks = chunk(`kd-${name}`, parsed.title, parsed.body, parsed.source);
    documents.push({
      file: name,
      id: `kd-${name}`,
      ...parsed,
      approved,
      status: approved ? 'READY' : 'PENDING_REVIEW',
      chunks: approved ? chunks : [],
      sectionCount: parsed.body.split(/\n\s*\n|(?=^##\s)/, -1).filter((s) => normalize(s) !== '').length,
      longestSection: Math.max(
        0,
        ...parsed.body.split(/\n\s*\n|(?=^##\s)/, -1).map((s) => normalize(s).length),
      ),
    });
  }
  return documents;
}

export function scoreChunk(item, queryTerms, queryVector) {
  const chunkTerms = medicalTerms(`${item.title} ${item.text}`);
  let hits = 0;
  for (const term of queryTerms) if (chunkTerms.has(term)) hits += 1;
  const termScore = queryTerms.size === 0 ? 0 : hits / queryTerms.size;
  const dense = cosine(queryVector, item.vector);
  const authority = item.source.startsWith('https://') ? AUTHORITY_HTTPS : AUTHORITY_OTHER;
  return Math.min(SCORE_CAP, termScore * WEIGHT_TERM + dense * WEIGHT_DENSE + authority);
}

/** Mirrors retrieve():86-102, with deterministic tie-breaking. */
export function retrieve(documents, query, maxResults = 5, minimumScore = DEFAULT_MIN_SCORE) {
  const normalized = normalize(query);
  if (normalized === '') return { grounded: false, evidence: [], note: '检索问题为空' };
  const queryTerms = medicalTerms(normalized);
  const queryVector = ngrams(normalized);
  const scored = documents
    .flatMap((document) => document.chunks)
    .map((item) => ({ ...item, score: scoreChunk(item, queryTerms, queryVector) }))
    .filter((item) => item.score >= minimumScore)
    .sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
  const limit = Math.max(1, Math.min(maxResults, RESULT_CAP));
  const evidence = scored.slice(0, limit).map((item) => ({
    id: item.id, title: item.title, source: item.source, score: round(item.score), text: item.text,
  }));
  return { grounded: evidence.length > 0, evidence, scored, queryTerms: [...queryTerms] };
}

/**
 * Checks that mirror assertions already pinned by the Java test suite. Changing the corpus can
 * silently break those tests, so they are replayed here first.
 */
export const PINNED_CHECKS = [
  {
    id: 'flow-nose-top1',
    pinnedBy: 'RuleBasedTriageEngineTest.unreviewedKnowledgeCannotInfluencePatientRetrieval',
    query: '流鼻涕',
    maxResults: 3,
    expect: (result) => result.grounded && result.evidence[0].title.includes('流鼻涕'),
    detail: (result) => `top1=${result.evidence[0]?.title} score=${result.evidence[0]?.score}`,
  },
  {
    id: 'cough-top1-official-source',
    pinnedBy: 'RuleBasedTriageEngineTest.bundledProfessionalKnowledgeIsSearchableAndTraceable',
    query: '咳嗽胸闷挂什么科',
    maxResults: 5,
    expect: (result) => result.grounded && result.evidence[0].source.startsWith('https://'),
    detail: (result) => `top1=${result.evidence[0]?.title} source=${result.evidence[0]?.source}`,
  },
  {
    id: 'dizziness-top1',
    pinnedBy: 'RuleBasedTriageEngineTest（断言证据标题含「头晕」）',
    query: '头晕',
    maxResults: 3,
    expect: (result) => result.grounded && result.evidence[0].title.includes('头晕'),
    detail: (result) => `top1=${result.evidence[0]?.title} score=${result.evidence[0]?.score}`,
  },
];

/** Diagnostic, not an assertion: how much of a document is outside the fixed term vocabulary. */
export function vocabularyCoverage(document) {
  const vocabulary = new Set(MEDICAL_TERMS);
  const topicTokens = document.topics
    .split(/[、,，\s]+/)
    .map((token) => token.trim())
    .filter(Boolean);
  const outside = topicTokens.filter((token) => !vocabulary.has(token));
  const matched = topicTokens.filter((token) => vocabulary.has(token));
  return { topicTokens, matched, outside, ratio: topicTokens.length === 0 ? 1 : matched.length / topicTokens.length };
}

/**
 * Out-of-vocabulary guard.
 *
 * `termScore` divides by `queryTerms.size()`, and `queryTerms` only ever contains the 52 words
 * above (score():104-107). A query built entirely from words outside that list therefore scores
 * `termScore = 0` and can only be rescued by the character-bigram channel:
 *
 *     score = 0.30 * cosine + 0.08   (for an https-sourced chunk)
 *     passing 0.28 requires cosine >= 0.667
 *
 * Measured on the current 12-chunk corpus, every query below stays ungrounded — a 20-character
 * verbatim quotation of a 150-character chunk tops out around 0.36 cosine, well short of 0.667.
 * So on the lexical path this is a latent trap rather than an active bug: it activates if chunks
 * get shorter, if the corpus gains short documents, or if the threshold moves.
 *
 * Scope limit, stated so nobody over-reads this guard: it mirrors the LEXICAL path only. When
 * embeddings are configured, HybridKnowledgeCatalog.java:43 derives `grounded` from the union of
 * semantic and lexical hits, and a pure paraphrase can satisfy it with no vocabulary word at all.
 * This tool does not and cannot verify that path.
 */
export const OOV_GUARD_QUERIES = [
  '脓毒症', '牙疼', '淋巴结肿大', '发热', '伤口流脓', '张口受限', '意识模糊',
  '怕冷寒战发抖', '鼻涕流个不停一直喷嚏', '脓毒症败血症', '张口受限牙关紧闭',
  '颈部肿胀发热', '意识模糊说话含糊', '伤口红肿渗液',
];

export function guardOutOfVocabulary(documents, minimumScore = DEFAULT_MIN_SCORE) {
  return OOV_GUARD_QUERIES.map((query) => {
    const result = retrieve(documents, query, 5, minimumScore);
    return {
      query,
      // If a future vocabulary change pulls this query into the term list, the query no longer
      // tests anything and the guard must report itself as void rather than pass silently.
      outsideVocabulary: result.queryTerms.length === 0,
      queryTerms: result.queryTerms,
      grounded: result.grounded,
      top: result.evidence[0]
        ? { title: result.evidence[0].title, score: result.evidence[0].score }
        : null,
    };
  });
}