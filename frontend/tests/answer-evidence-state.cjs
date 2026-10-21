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
test('non-medical service intent is not reported as an empty RAG search', () => {
  const state={...meta,modelStatus:'SERVICE_ONLY',knowledgeHits:0,answerEvidence:{retrievalStatus:'NOT_APPLICABLE'}}
  assert.match(context.evidenceMethod(state),/未执行 RAG.*与检索未命中不同/)
  assert.match(context.answerMethod(state),/未调用回答生成模型/)
})

test('source excerpt is distinct from passed generation and never says the question was resolved',()=>{
  const state={...meta,modelStatus:'SOURCE_EXCERPT',answerEvidence:{retrievalStatus:'MATCHED',validationStatus:'SOURCE_EXCERPT_VERIFIED',supportReview:{status:'REJECTED'},adoptedReferenceIds:['E1']}}
  assert.match(context.evidenceMethod(state),/1 份资料原文.*不代表问题已被完整回答/)
  assert.match(context.answerMethod(state),/资料原文摘录.*未采用被拒绝/)
  assert.doesNotMatch(context.answerMethod(state),/^AI 模型已参与回答/)
})
test('blocked answer does not claim validated adoption', () => assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', validationStatus: 'CONTENT_BLOCKED' } }), /未采用/))
test('safety takeover remains independent', () => assert.equal(context.evidenceMethod({ ...meta, modelStatus: 'SAFETY_RULE' }), '危险提醒优先于知识检索'))
test('new operational failure is not presented as a medical fallback', () => assert.match(context.answerMethod({ ...meta, modelStatus: 'FALLBACK', answerEvidence: { failure: { code: 'MODEL_TIMEOUT' } } }), /失败提示，未采用模型生成内容/))
test('legacy fallback keeps its historical disclosure', () => assert.match(context.answerMethod({ ...meta, modelStatus: 'FALLBACK' }), /保守回答/))

test('withdrawn sources take precedence over zero visible hits and a prior passed review', () => {
  const state={...meta,modelStatus:'VALIDATION_BLOCKED',knowledgeHits:0,answerEvidence:{
    retrievalStatus:'ANSWER_SOURCE_CHANGED',validationStatus:'BLOCKED',adoptedReferenceIds:[],supportReview:{status:'PASSED'}}}
  assert.match(context.evidenceMethod(state), /已发生变化.*已隐藏/)
  assert.doesNotMatch(context.evidenceMethod(state), /^未找到/)
  assert.match(context.answerMethod(state), /参考资料有效性未确认/)
  assert.doesNotMatch(context.answerMethod(state), /生成内容未通过校验/)
})

test('unavailable or untracked source checks do not imply missing documents or depleted quota', () => {
  for(const code of ['ANSWER_SOURCE_UNAVAILABLE','ANSWER_SOURCE_UNTRACKED']){
    const state={...meta,modelStatus:'VALIDATION_BLOCKED',knowledgeHits:0,answerEvidence:{retrievalStatus:'MATCHED',failure:{code}}}
    assert.match(context.evidenceMethod(state), /无法核实.*不代表没有相关资料/)
    assert.match(context.answerMethod(state), /不是模型额度耗尽/)
  }
  assert.equal(context.evidenceMethod({...meta,knowledgeHits:0}), '未找到可引用的医学知识片段')
})

test('source metadata cannot override safety or compliance terminal explanations', () => {
  const state={...meta,answerEvidence:{retrievalStatus:'ANSWER_SOURCE_CHANGED'}}
  assert.equal(context.evidenceMethod({...state,modelStatus:'SAFETY_RULE'}), '危险提醒优先于知识检索')
  assert.match(context.answerMethod({...state,modelStatus:'SAFETY_RULE'}), /安全规则/)
  assert.match(context.answerMethod({...state,modelStatus:'POLICY_REFUSAL'}), /合规拒答/)
})

test('support rejection distinguishes retrieved evidence from accepted answer', () => {
  assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', supportReview: { status: 'REJECTED' } } }), /检索到 2 条资料.*未采用/)
  assert.match(context.evidenceMethod({ ...meta, answerEvidence: { retrievalStatus: 'MATCHED', supportReview: { status: 'UNAVAILABLE' } } }), /核对暂未完成/)
})
test('backend service messages are separate and old messages do not invent them', () => {
  assert.equal(context.serviceMessages(meta).length, 0)
  assert.equal(context.serviceMessages({ ...meta, answerEvidence: { serviceNotice: { messages: ['科室查询失败'] } } })[0], '科室查询失败')
  assert.doesNotMatch(context.answerMethod({ ...meta, modelStatus: 'VALIDATION_BLOCKED' }), /保守回答/)
})
