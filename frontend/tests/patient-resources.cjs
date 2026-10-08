const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/patientResources.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {patientResources}=mod.exports
test('one failed resource does not discard successful patient records',async()=>{
  const results=await patientResources({doctors:async()=>[{id:'d'}],visits:async()=>{throw Error('fixture')},sessions:async()=>[]})
  assert.deepEqual(results[0].outcome,{status:'fulfilled',value:[{id:'d'}]})
  assert.equal(results[1].outcome.status,'rejected')
  assert.deepEqual(results[2].outcome,{status:'fulfilled',value:[]})
})
test('legitimate empty data differs from an unavailable endpoint',async()=>{
  const results=await patientResources({visits:async()=>[],timeline:async()=>{throw Error('fixture')}})
  assert.deepEqual(results[0].outcome,{status:'fulfilled',value:[]})
  assert.equal(results[1].outcome.status,'rejected')
})
