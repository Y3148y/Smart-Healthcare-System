const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { test } = require('node:test')
const assert = require('node:assert/strict')
const source = fs.readFileSync(path.join(__dirname, '../src/features/triage/TriagePage.vue'), 'utf8')
const functionSource = source.slice(source.indexOf('function evidenceMethod('), source.indexOf('function answerMethod('))
const context = {}
vm.runInNewContext(ts.transpileModule(functionSource, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context)
const answerSource = source.slice(source.indexOf('function answerMethod('), source.indexOf('async function startTriage('))
vm.runInNewContext(ts.transpileModule(answerSource, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context)
const meta = { modelStatus: 'LIVE', knowledgeHits: 2, localToolCalls: 1, toolFailures: 0 }
test('retrieved does not mean adopted', () => {
  const text = context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', validationStatus: 'REFERENCE_INTEGRITY_PASSED', adoptedReferenceIds: [] } })
  assert.match(text, /检索到 2 条/)
  assert.match(text, /引用 0 条/)
})
test('legacy records do not invent citations', () => assert.match(context.evidenceMethod(meta), /旧记录未采集/))
test('dependency failure does not imply no knowledge', () => assert.match(context.evidenceMethod({ ...meta, knowledgeHits: 0, answerEvidence: { retrievalStatus: 'DEPENDENCY_UNAVAILABLE' } }), /不代表没有相关资料/))
test('blocked answer does not claim validated adoption', () => assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', validationStatus: 'CONTENT_BLOCKED' } }), /未采用/))
test('safety takeover remains independent', () => assert.equal(context.evidenceMethod({ ...meta, modelStatus: 'SAFETY_RULE' }), '危险提醒优先于知识检索'))
test('new operational failure is not presented as a medical fallback', () => assert.match(context.answerMethod({ ...meta, modelStatus: 'FALLBACK', answerEvidence: { failure: { code: 'MODEL_TIMEOUT' } } }), /失败提示，未采用模型生成内容/))
test('legacy fallback keeps its historical disclosure', () => assert.match(context.answerMethod({ ...meta, modelStatus: 'FALLBACK' }), /保守回答/))

test('support rejection distinguishes retrieved evidence from accepted answer', () => {
  assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', supportReview: { status: 'REJECTED' } } }), /检索到 2 条资料.*未采用/)
  assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', supportReview: { status: 'UNAVAILABLE' } } }), /核对暂未完成/)
})
test('backend service messages are separate and old messages do not invent them', () => {
  assert.equal(context.serviceMessages(meta).length, 0)
  assert.equal(context.serviceMessages({ ...meta, answerEvidence: { serviceNotice: { messages: ['科室查询失败'] } } })[0], '科室查询失败')
  assert.doesNotMatch(context.answerMethod({ ...meta, modelStatus: 'VALIDATION_BLOCKED' }), /保守回答/)
})
