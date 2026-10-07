export interface IntakeSource { sourceId: string; publisher: string; url: string; fetchedAt: string | null; rawSha256: string | null }
export interface IntakeMetadata { language: string; contentKind: string; topics: string[]; population: string[];
  exclusions: string[]; prerequisites: string[]; evidenceUses: string[]; permissionEvidence: string | null; sources: IntakeSource[] }
export interface IntakeCandidate { title: string; body: string; metadata: IntakeMetadata; warnings: string[] }

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('')
}

// Canonical drafts are a document plus hash-bound provenance and source snapshots.
// No network fetch or approval is performed while reading these files.
export async function parseKnowledgeMarkdown(files: Record<string, string>): Promise<IntakeCandidate> {
  const text = files['document.zh-CN.md']?.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n')
  const match = text && /^---\n([\s\S]*?)\n---\n([\s\S]+)$/.exec(text)
  if (!match) throw new Error('请选择 document.zh-CN.md 及同目录审计和来源文件')
  const header = JSON.parse(match[1])
  const provenance = files['provenance.json']
  if (header.schemaVersion !== 1 || header.approvalStatus !== 'PENDING_REVIEW'
      || header.clinicalReviewStatus !== 'NOT_REVIEWED' || header.provenanceFile !== 'provenance.json'
      || header.metadata !== undefined || typeof header.documentId !== 'string' || !header.documentId.trim()
      || typeof provenance !== 'string' || await sha256(provenance) !== header.provenanceSha256)
    throw new Error('待审状态或审计文件哈希不一致')
  const audit = JSON.parse(provenance)
  if (audit.documentId !== header.documentId || audit.approvalStatus !== header.approvalStatus
      || audit.clinicalReviewStatus !== header.clinicalReviewStatus || !audit.metadata
      || !Array.isArray(audit.sourceSnapshots) || !audit.sourceSnapshots.length || audit.sourceSnapshots.length > 8)
    throw new Error('审计文件与资料不一致')
  const names = new Set<string>(), ids = new Set<string>()
  for (const snapshot of audit.sourceSnapshots) {
    if (!/^source(?:\.[a-z]+)?\.en\.md$/.test(snapshot.file || '') || names.has(snapshot.file)
        || typeof snapshot.sourceId !== 'string' || ids.has(snapshot.sourceId)
        || !/^[a-f0-9]{64}$/.test(snapshot.sha256 || '')) throw new Error('来源快照声明不合格')
    names.add(snapshot.file); ids.add(snapshot.sourceId)
    const source = files[snapshot.file]?.replace(/\r\n/g, '\n')
    if (typeof source !== 'string' || ![await sha256(source), await sha256(source.replace(/\n$/, ''))].includes(snapshot.sha256))
      throw new Error('缺少来源快照或来源快照哈希不一致')
  }
  if (!names.has('source.en.md') || !Array.isArray(audit.metadata.sources)
      || audit.metadata.sources.some((source: IntakeSource) => !ids.has(source.sourceId)))
    throw new Error('来源快照不完整')
  return parseKnowledgeIntake(JSON.stringify({schemaVersion: 1, reviewStatus: 'PENDING', title: header.title,
    body: match[2], extraction: {contentSha256: await sha256(match[2]), warnings: audit.extractionWarnings ?? []}, metadata: {
      ...audit.metadata, permissionStatus: 'pending', permissionEvidence: null
    }}), true)
}

export async function parseKnowledgeIntake(text: string, canonical = false): Promise<IntakeCandidate> {
  const data = JSON.parse(text)
  if (data.schemaVersion !== 1 || data.reviewStatus !== 'PENDING' || !data.metadata || !data.extraction)
    throw new Error('不是有效的待审资料包')
  if (typeof data.title !== 'string' || !data.title.trim() || data.title.length > 160
      || typeof data.body !== 'string' || !data.body.trim() || data.body.length > 100000)
    throw new Error('待审包标题或正文不合格')
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data.body))
  const hash = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('')
  if (data.extraction.contentSha256 !== hash) throw new Error('待审正文哈希不一致，请重新生成资料包')
  const metadata = data.metadata
  if (metadata.schemaVersion !== 1 || !(canonical ? ['source_extract', 'reviewed_summary'] : ['source_extract']).includes(metadata.contentKind)
      || metadata.permissionStatus !== 'pending' || metadata.permissionEvidence != null)
    throw new Error('资料包不得携带已批准许可')
  if (!Array.isArray(metadata.sources) || !metadata.sources.length || metadata.sources.length > (canonical ? 8 : 1))
    throw new Error('资料包来源数量不合格')
  const sourceIds = new Set<string>()
  for (const source of metadata.sources) {
  const url = new URL(source.url)
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password
      || !/^[a-f0-9]{64}$/.test(source.rawSha256) || !Number.isFinite(Date.parse(source.fetchedAt))
      || typeof source.sourceId !== 'string' || !source.sourceId.trim()
      || typeof source.publisher !== 'string' || !source.publisher.trim()
      || source.sourceId.length > 128 || source.publisher.length > 240 || source.url.length > 2000
      || sourceIds.has(source.sourceId)) throw new Error('来源溯源字段不合格')
  sourceIds.add(source.sourceId)
  }
  for (const field of ['topics', 'population', 'exclusions', 'prerequisites', 'evidenceUses']) {
    if (!Array.isArray(metadata[field]) || metadata[field].length > 32
        || metadata[field].some((value: unknown) => typeof value !== 'string' || !value.trim() || value.length > 240))
      throw new Error('待审适用范围字段不合格')
  }
  if (typeof metadata.language !== 'string' || !metadata.language.trim()) throw new Error('资料语言缺失')
  if (!Array.isArray(data.extraction.warnings) || data.extraction.warnings.some((value: unknown) => typeof value !== 'string'))
    throw new Error('抽取告警字段不合格')
  return {title: data.title, body: data.body, metadata, warnings: data.extraction.warnings}
}
