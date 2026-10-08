const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/triage/conversationProcessing.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {conversationProcessing,conversationSendBlocked}=mod.exports
test('persisted processing state blocks resubmission after browser reload',()=>{
  assert.equal(conversationProcessing('处理中',false),true)
})
test('local request remains blocked before refreshed server state arrives',()=>{
  for(const state of [undefined,'已完成分诊','待补充信息','待重试'])assert.equal(conversationProcessing(state,true),true)
})
test('terminal and legacy states do not become permanently blocked',()=>{
  for(const state of [undefined,'已完成分诊','待补充信息','待重试','紧急提示','建议尽快就医'])
    assert.equal(conversationProcessing(state,false),false)
})

test('emergency stops ordinary messages while urgent and retry states follow existing backend policy',()=>{
  assert.equal(conversationSendBlocked('紧急提示',false),true)
  assert.equal(conversationSendBlocked('处理中',false),true)
  for(const state of [undefined,'待补充信息','待重试','已完成分诊','建议尽快就医'])assert.equal(conversationSendBlocked(state,false),false)
})
