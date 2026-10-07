import { createHash } from 'node:crypto'

// Engineering inventory only: never asserts medical correctness or permission.
export function auditBundledDocument(path, markdown) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n')
  const title = lines.find(line => line.startsWith('# '))?.slice(2).trim() || null
  const sources = lines.filter(line => /^(来源|补充来源)：/.test(line))
    .map(line => line.slice(line.indexOf('：') + 1).trim())
  const body = lines.filter(line => !/^# |^(来源|补充来源|主题)：/.test(line)).join('\n').trim()
  const issues = ['LEGACY_METADATA_UNKNOWN', 'PERMISSION_NOT_VERIFIED', 'CLINICAL_REVIEW_NOT_VERIFIED']
  if (!title) issues.push('TITLE_MISSING')
  if (!sources.length) issues.push('SOURCE_MISSING')
  if (sources.some(source => { try { const url = new URL(source); return url.protocol !== 'https:' } catch { return true } })) issues.push('SOURCE_INVALID')
  if ([...body].length < 500) issues.push('SHORT_BODY_REVIEW_REQUIRED')
  return { path, title, sources, bodyCodePoints: [...body].length,
    bodySha256: createHash('sha256').update(body).digest('hex'), issues,
    eligibleForAutomaticApproval: false }
}
