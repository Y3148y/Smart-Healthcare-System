const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('patient assessment does not present an uncalibrated triage heuristic as a probability', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/features/triage/components/AssessmentCard.vue'), 'utf8')
  assert.match(source, /分诊参考分值：未校准/)
  assert.match(source, /不代表诊断准确率或真实概率/)
  assert.doesNotMatch(source, /置信度：\{\{\s*shown\.result\.confidence\s*\}\}%/)
})
