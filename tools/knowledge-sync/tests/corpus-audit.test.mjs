import test from 'node:test'
import assert from 'node:assert/strict'
import { auditBundledDocument } from '../lib/corpus-audit.mjs'

test('short legacy documents are inventoried, not silently certified or rewritten', () => {
  const report = auditBundledDocument('example.md', '# Title\n来源：https://example.org/page\n主题：example\n\nShort body.')
  assert.equal(report.bodyCodePoints, 11)
  assert.ok(report.issues.includes('SHORT_BODY_REVIEW_REQUIRED'))
  assert.ok(report.issues.includes('PERMISSION_NOT_VERIFIED'))
  assert.equal(report.eligibleForAutomaticApproval, false)
  assert.equal(report.bodySha256.length, 64)
  assert.ok(!('body' in report))
})
test('missing and invalid sources stay explicit; long text does not imply approval', () => {
  assert.ok(auditBundledDocument('empty.md', '').issues.includes('SOURCE_MISSING'))
  const report = auditBundledDocument('bad.md', '# T\n来源：not-url\n\n' + '正文'.repeat(300))
  assert.ok(report.issues.includes('SOURCE_INVALID'))
  assert.ok(!report.issues.includes('SHORT_BODY_REVIEW_REQUIRED'))
  assert.equal(report.eligibleForAutomaticApproval, false)
})
