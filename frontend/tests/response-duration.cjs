const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/triage/responseDuration.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {responseDuration}=mod.exports
test('actual client measurement retains request scope',()=>{
  assert.deepEqual(responseDuration(12.5,{turnTrace:{elapsedMs:10000}}),{label:'本次请求用时',seconds:12.5})
})
test('persisted backend trace is identified separately on history read',()=>{
  assert.deepEqual(responseDuration(undefined,{turnTrace:{elapsedMs:30726}}),{label:'后台本轮处理用时',seconds:30.726})
  assert.deepEqual(responseDuration(undefined,{turnTrace:{elapsedMs:0}}),{label:'后台本轮处理用时',seconds:0})
})
test('legacy model-only timing cannot masquerade as whole-turn duration',()=>{
  assert.equal(responseDuration(undefined,{answerEvidence:{elapsedMs:5000}}),null)
  assert.equal(responseDuration(undefined),null)
})
test('invalid, missing and nonnumeric durations do not invent time',()=>{
  for(const value of [undefined,null,NaN,Infinity,-1,'1000']){
    assert.equal(responseDuration(value,{turnTrace:{elapsedMs:value}}),null)
  }
})
