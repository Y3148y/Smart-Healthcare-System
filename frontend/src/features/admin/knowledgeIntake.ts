export interface IntakeSource { sourceId: string; publisher: string; url: string; fetchedAt: string | null; rawSha256: string | null }
export interface IntakeMetadata { language: string; contentKind: string; topics: string[]; population: string[];
  exclusions: string[]; prerequisites: string[]; evidenceUses: string[]; permissionEvidence: string | null; sources: IntakeSource[] }
export interface IntakeCandidate { title: string; body: string; metadata: IntakeMetadata; warnings: string[] }

export async function parseKnowledgeIntake(text: string): Promise<IntakeCandidate> {
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
  if (metadata.schemaVersion !== 1 || metadata.contentKind !== 'source_extract'
      || metadata.permissionStatus !== 'pending' || metadata.permissionEvidence != null)
    throw new Error('资料包不得携带已批准许可')
  if (!Array.isArray(metadata.sources) || metadata.sources.length !== 1) throw new Error('资料包应包含一个可追溯来源')
  const source = metadata.sources[0]
  const url = new URL(source.url)
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password
      || !/^[a-f0-9]{64}$/.test(source.rawSha256) || !Number.isFinite(Date.parse(source.fetchedAt))
      || typeof source.sourceId !== 'string' || !source.sourceId.trim()
      || typeof source.publisher !== 'string' || !source.publisher.trim()) throw new Error('来源溯源字段不合格')
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
