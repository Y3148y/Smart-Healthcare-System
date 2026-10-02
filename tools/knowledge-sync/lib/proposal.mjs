/**
 * Rewrite worksheet generator.
 *
 * The point of this repository is that nothing scraped ever becomes patient-facing text on its
 * own. docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md:42 requires short original paragraphs plus the
 * original URL, not copied pages. So for every new or changed source we emit a worksheet with the
 * provenance header pre-filled, the extracted text attached for reading, and the body left blank
 * for a human or a judgement-capable AI to write — after which the owner approves a separate
 * commit.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { CACHE_DIR, REPORTS_DIR } from './registry.mjs';
import { htmlToText } from './extract.mjs';
import { MEDICAL_TERMS } from './verify.mjs';

const CHECKLIST = [
  '来源是否官方机构或公立医院？（若是商业内容、论坛或问答，直接丢弃本条）',
  '抽取正文里是否含诊断、处方、药名或剂量？若有，删掉——本项目不输出这些。',
  '是否含广告、促销、导航残留？（抽取器是粗筛，必须人工确认）',
  '这段内容对应哪个已登记缺口？写不出缺口编号就说明不需要它。',
  '是否需要补写「出现 X 应立即线下就医」的升级提示？',
  '中文表述是否忠于英文/中文原文？改写不是意译，偏差要写进备注。',
  '是否已在正文里标明「未经临床审核」？',
];

export function snapshotPath(source) {
  return join(CACHE_DIR, source.id, 'snapshot.html');
}

export function extractSource(source) {
  const path = snapshotPath(source);
  if (!existsSync(path)) return null;
  return htmlToText(readFileSync(path, 'utf8'));
}

export function buildWorksheet(source, record) {
  const text = extractSource(source);
  const generatedAt = new Date().toISOString();
  const header = [
    `# 改写工作单：${source.name}`,
    '',
    '> 本文件由 `tools/knowledge-sync` 生成。抽取正文**仅供阅读**，不得复制进语料；',
    '> 未经改写与 owner 批准，本源不会对患者可见。',
    '',
    '## 来源登记',
    '',
    '| 字段 | 值 |',
    '| --- | --- |',
    `| id | \`${source.id}\` |`,
    `| 发布方 | ${source.publisher} |`,
    `| 辖区 | ${source.jurisdiction} |`,
    `| 类型 | ${source.type} |`,
    `| URL | ${source.url || '（未登记）'} |`,
    `| 许可与边界 | ${source.licenseNote} |`,
    `| 覆盖缺口 | ${source.covers || '（未填写）'} |`,
    `| 建议目标语料 | ${source.targetDoc || '（未定）'} |`,
    `| 抓取时间 | ${record?.fetchedAt ?? '（未抓取）'} |`,
    `| HTTP | ${record?.httpStatus ?? '-'} / ${record?.status ?? '-'} |`,
    `| sha256 | ${record?.sha256 ?? '-'} |`,
    `| 许可需复核 | 是——本表许可判断由人做，脚本不判定合规 |`,
    '',
    '## 检查清单',
    '',
    ...CHECKLIST.map((item, index) => `${index + 1}. [ ] ${item}`),
    '',
    '## 待填语料正文',
    '',
    '按仓库既有格式改写：`# 标题` / `来源：https://…` / `主题：` / 正文。每个段落控制在 400 字内，',
    '以命中单块分块（InMemoryKnowledgeCatalog CHUNK_SIZE=420）；正文首段或末段必须写明「未经临床审核」。',
    '',
    `检索词表目前只有 ${MEDICAL_TERMS.length} 个词，` +
      '词表外的词对词法得分贡献为 0，只能靠 bigram 权重（上限约 0.38，阈值 0.28）。',
    '所以主题与正文应尽量复用下列词语：',
    '',
    `> ${MEDICAL_TERMS.join('、')}`,
    '',
    '```markdown',
    `# ${source.name}`,
    source.url ? `来源：${source.url}` : '来源：（待补官方 URL，必须是 https:// 开头，否则该文档不会对患者可见）',
    `主题：（从上面 ${MEDICAL_TERMS.length} 个词里挑；实在没有合适词就不要硬凑）`,
    '',
    '（在此写改写后的短段落）',
    '```',
    '',
    '## 抽取正文（机器抽取，未经核校）',
    '',
    text ? text : '（本次未取到快照：可能未抓取、被 robots 拒绝、内容类型非 HTML，或被 WAF 拦截。）',
    '',
    '---',
    '',
    `生成时间：${generatedAt}`,
  ];
  return header.join('\n');
}

export function writeWorksheets(sources, records) {
  mkdirSync(join(REPORTS_DIR, 'proposals'), { recursive: true });
  const written = [];
  for (const source of sources) {
    const record = records[source.id];
    if (!record || record.status === 'unchanged') continue;
    const path = join(REPORTS_DIR, 'proposals', `${source.id}.md`);
    writeFileSync(path, `${buildWorksheet(source, record)}\n`, 'utf8');
    written.push(path);
  }
  return written;
}