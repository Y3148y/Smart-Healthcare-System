const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),{transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/admin/runtimeTrace.ts'),mod=new Module(file,module)
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {emptyTraceDetails,traceDetailsLoader}=mod.exports
const first='00000000-0000-4000-8000-000000000001',second='00000000-0000-4000-8000-000000000002'
const row=(id,traceId,time)=>({id,traceId,time,purpose:'fixture',model:'fixture',inputTokens:null,outputTokens:null,elapsedMs:1,success:true,tools:[]})
test('trace fetch sorts its own records without changing the returned source array',async()=>{
  const source=[row('b',first,'2026-10-17T10:00:02'),row('a',first,'2026-10-17T10:00:01')]
  const state=emptyTraceDetails(),loader=traceDetailsLoader(state,async id=>{assert.equal(id,first);return source})
  await loader.load(first);assert.deepEqual(state.calls.map(c=>c.id),['a','b'])
  assert.equal(source[0].id,'b');assert.equal(state.loading,false);assert.equal(state.error,'')
})
test('late response cannot overwrite another selected trace or a cleared page',async()=>{
  const state=emptyTraceDetails(),pending=[]
  const loader=traceDetailsLoader(state,id=>new Promise(resolve=>pending.push({id,resolve})))
  const a=loader.load(first),b=loader.load(second)
  pending[1].resolve([row('second',second,'2026')]);await b
  pending[0].resolve([row('first',first,'2026')]);await a
  assert.equal(state.traceId,second);assert.equal(state.calls[0].id,'second')
  const c=loader.load(first);loader.clear();pending[2].resolve([row('first',first,'2026')]);await c
  assert.deepEqual(state,emptyTraceDetails())
})
test('mixed trace responses, duplicate records and raw errors are never displayed as valid history',async()=>{
  for(const response of [[row('wrong',second,'2026')],[row('same',first,'2026'),row('same',first,'2026')]]){
    const state=emptyTraceDetails(),loader=traceDetailsLoader(state,async()=>response)
    await loader.load(first);assert.deepEqual(state.calls,[]);assert.ok(state.error)
  }
  const state=emptyTraceDetails(),loader=traceDetailsLoader(state,async()=>{throw Error('PRIVATE_RAW_ERROR')})
  await loader.load(first);assert.ok(!state.error.includes('PRIVATE_RAW_ERROR'));assert.equal(state.loading,false)
})
test('invalid trace cannot issue a request and the admin page uses the existing technical endpoint',async()=>{
  let requests=0;const state=emptyTraceDetails(),loader=traceDetailsLoader(state,async()=>{requests++;return []})
  await loader.load('invalid');assert.equal(requests,0)
  const page=fs.readFileSync(path.resolve(__dirname,'../src/features/admin/RuntimeObservePage.vue'),'utf8')
  assert.ok(page.includes('/admin/calls?traceId='));assert.ok(page.includes('查看本轮链路'))
  assert.ok(page.includes('不代表完整阶段或因果顺序'));assert.ok(page.includes('onBeforeUnmount(()=>traceLoader.clear())'))
})
test('trace identifiers follow server UUID normalization, including legacy versions',async()=>{
  const trace='abcdefab-0000-0000-0000-000000000001',state=emptyTraceDetails()
  const loader=traceDetailsLoader(state,async id=>{assert.equal(id,trace);return [row('legacy',trace,'2026')]})
  await loader.load(trace.toUpperCase());assert.equal(state.error,'');assert.equal(state.calls[0].id,'legacy')
})
