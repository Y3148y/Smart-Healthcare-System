const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/admin/toolCenter.ts')
const mod=new Module(file,module);mod.paths=Module._nodeModulePaths(path.dirname(file))
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {toolCenter,toolParameter}=mod.exports
const fixture={code:'department_search',name:'Synthetic directory',description:'Fixture',enabled:true}

test('admin shell does not preload unrelated or duplicate backend resources',()=>{
  const app=fs.readFileSync(path.resolve(__dirname,'../src/App.vue'),'utf8')
  assert.doesNotMatch(app,/loadAdmin|api\([^\n]*['"]\/admin\//)
  const shell=fs.readFileSync(path.resolve(__dirname,'../src/features/admin/AdminPage.vue'),'utf8')
  assert.match(shell,/<ToolsPage v-else-if="page==='tools'"/)
  assert.doesNotMatch(shell,/defineEmits|knowledgeRuntime|HumanReviewQueueItem/)
})
test('failed load is visible, is not an empty successful list, and explicit refresh recovers',async()=>{
  let fail=true,calls=0
  const state=toolCenter(async()=>{calls++;if(fail)throw Error('HTTP 503');return [fixture]})
  await state.load()
  assert.equal(state.loaded.value,false);assert.equal(state.loading.value,false)
  assert.match(state.error.value,/HTTP 503/);assert.equal(state.needsRefresh.value,true)
  assert.equal(calls,1)
  fail=false;await state.load()
  assert.equal(state.loaded.value,true);assert.equal(state.needsRefresh.value,false)
  assert.equal(state.error.value,'');assert.equal(state.tools.value[0].code,fixture.code)
})
test('uncertain toggle is never automatically replayed and requires a fresh read',async()=>{
  const calls=[];let enabled=true
  const state=toolCenter(async(url,init)=>{
    calls.push([url,init?.method])
    if(init?.method==='PATCH'){enabled=false;throw Error('connection lost after write')}
    return [{...fixture,enabled}]
  })
  await state.load();await state.toggle(fixture.code)
  assert.equal(state.needsRefresh.value,true);assert.match(state.error.value,/结果待确认/)
  await state.toggle(fixture.code)
  assert.equal(calls.filter(([,method])=>method==='PATCH').length,1)
  await state.load()
  assert.equal(state.tools.value[0].enabled,false);assert.equal(state.needsRefresh.value,false)
})
test('busy toggle blocks repeated clicks and refresh without extra backend requests',async()=>{
  let release,writes=0,reads=0
  const state=toolCenter(async(url,init)=>{
    if(init?.method==='PATCH'){writes++;return new Promise(resolve=>{release=resolve})}
    reads++;return [fixture]
  })
  await state.load();const pending=state.toggle(fixture.code)
  await state.toggle(fixture.code);await state.load()
  assert.equal(writes,1);assert.equal(reads,1)
  release({...fixture,enabled:false});await pending
  assert.equal(state.tools.value[0].enabled,false);assert.equal(state.busy.value,false)
})
test('trial only sends the actual protocol parameter and preserves actual result',async()=>{
  const calls=[],result={data:{exists:false},trace:{success:true,outcome:'not found',elapsedMs:7}}
  const state=toolCenter(async(url,init)=>{calls.push([url,init]);return init?result:[fixture]})
  await state.load();await state.run(fixture.code,' Synthetic ')
  assert.deepEqual(JSON.parse(calls[1][1].body),{department:'Synthetic'})
  assert.deepEqual(state.execution.value,result);assert.match(state.notice.value,/not found/)
  assert.equal(toolParameter('medical_knowledge_retrieve'),'query')
  assert.equal(toolParameter('symptom_tag_search'),'query');assert.equal(toolParameter('unknown'),null)
})
test('unknown, disabled, blank and overlong trials never execute',async()=>{
  let writes=0
  const state=toolCenter(async(url,init)=>{if(init)writes++;return [fixture,{...fixture,code:'symptom_tag_search',enabled:false}]})
  await state.load()
  for(const [code,input] of [['unknown','test'],['symptom_tag_search','test'],[fixture.code,' '],[fixture.code,'x'.repeat(2201)]])
    await state.run(code,input)
  assert.equal(writes,0)
})
test('tool failure is different from transport failure and never fabricates success',async()=>{
  let transport=false
  const result={data:null,trace:{success:false,outcome:'failed',error:'Synthetic unavailable'}}
  const state=toolCenter(async(url,init)=>{if(!init)return [fixture];if(transport)throw Error('HTTP 503');return result})
  await state.load();await state.run(fixture.code,'test')
  assert.match(state.error.value,/工具执行失败/);assert.equal(state.notice.value,'')
  assert.deepEqual(state.execution.value,result)
  transport=true;await state.run(fixture.code,'test')
  assert.match(state.error.value,/工具请求失败/);assert.equal(state.execution.value,null)
  assert.equal(state.busy.value,false)
})
