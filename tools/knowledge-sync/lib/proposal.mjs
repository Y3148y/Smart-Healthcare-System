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
import { htmlToText, extractHtml } from './extract.mjs';

const CHECKLIST = [
  '来源是否官方机构或公立医院？（若是商业内容、论坛或问答，直接丢弃本条）',
  '是否包含诊断、药物或剂量等超出患者端输出范围的内容？保留原始来源证据，不改写原文；在待审内容中声明范围与排除项。',
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
  const extraction = existsSync(snapshotPath(source))
    ? extractHtml(readFileSync(snapshotPath(source), 'utf8')) : null;
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
    `| 抽取版本 | ${extraction?.extractionVersion ?? '-'} |`,
    `| 抽取区域 | ${extraction?.region ?? '-'} |`,
    `| 抽取告警 | ${extraction?.warnings.join(', ') || '-'} |`,
    '',
    '## 检查清单',
    '',
    ...CHECKLIST.map((item, index) => `${index + 1}. [ ] ${item}`),
    '',
    '## 待填语料正文',
    '',
    '保持原有标题、条件、例外、警示和段落关系。正文由后端统一切分，不为命中单块而删减必要上下文。',
    '通过管理员知识导入提交正文和结构化元数据：来源、语言、内容类型、适用人群、排除项、证据用途、许可状态与证明。初始待审；未经临床审核。',
    '',
    '禁止为了检索得分硬凑词或预设答案。当前在线检索为混合链路；旧 verify 词表不是语料编写规范。',
    '',
    '```markdown',
    `# ${source.name}`,
    source.url ? `来源：${source.url}` : '来源：（待补官方 URL，必须是 https:// 开头，否则该文档不会对患者可见）',
    '主题：（忠于原文，不为召回添加无关症状或科室）',
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
