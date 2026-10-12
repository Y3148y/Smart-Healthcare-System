const test = require('node:test')
const assert = require('node:assert/strict')
const { inspectText, inspectTrackedFiles } = require('./check-public-content.cjs')

test('reports credential locations without returning secret values', () => {
  const secret = 'sk-' + 'x'.repeat(32)
  const github = 'ghp_' + 'y'.repeat(36)
  const findings = inspectText(`safe\n${secret}\n${github}`, 'sample.txt')
  assert.deepEqual(findings, [
    { file: 'sample.txt', line: 2, rule: 'provider-api-key' },
    { file: 'sample.txt', line: 3, rule: 'github-token' }
  ])
  assert.ok(!JSON.stringify(findings).includes(secret))
})

test('detects a private key marker and leaves placeholders alone', () => {
  assert.deepEqual(inspectText('AI_API_KEY=sk-your-key-here', 'example.env'), [])
  const marker = ['-----BEGIN', 'PRIVATE KEY-----'].join(' ')
  assert.deepEqual(inspectText(marker, 'key.txt'), [
    { file: 'key.txt', line: 1, rule: 'private-key-block' }
  ])
})

test('current tracked snapshot has no obvious credential pattern', () => {
  assert.deepEqual(inspectTrackedFiles().findings, [])
})
