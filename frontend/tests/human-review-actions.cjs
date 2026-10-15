const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/admin/humanReviewActions.ts')
const mod=new Module(file,module);mod.paths=Module._nodeModulePaths(path.dirname(file))
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {humanReviewActions}=mod.exports
const item={id:'fixture',status:'PENDING',summaryAccessible:true,createdAt:'2026-01-01'}
test('queue load blocks competing summary reads and mutations',async()=>{
  let resolve,calls=0
  const s=humanReviewActions(()=>{calls++;return new Promise(r=>resolve=r)})
  const pending=s.load();await s.load();await s.view(item.id);await s.change(item.id,'CLOSED')
  assert.equal(calls,1);assert.equal(s.ready.value,false)
  resolve([item]);await pending;assert.equal(s.ready.value,true)
})
test('uncertain mutation clears sensitive summary and requires refresh without replay',async()=>{
  let writes=0
  const s=humanReviewActions(async(p,o)=>{if(o){writes++;throw Error('network')}return p.endsWith('/summary')?{request:item,patientStatement:'fixture'}:[item]})
  await s.load();await s.view(item.id);assert.ok(s.summary.value)
  await s.change(item.id,'CLOSED');await s.change(item.id,'CLOSED');await s.view(item.id)
  assert.equal(writes,1);assert.equal(s.summary.value,null);assert.equal(s.ready.value,false)
  assert.match(s.error.value,/不会自动重试/)
  await s.load();assert.equal(s.ready.value,true);assert.equal(writes,1)
})
test('busy summary read cannot race refresh or a status write',async()=>{
  let resolve,calls=0
  const s=humanReviewActions(async p=>{calls++;return p.endsWith('/summary')?new Promise(r=>resolve=r):[item]})
  await s.load();const pending=s.view(item.id)
  await s.load();await s.change(item.id,'ACCEPTED');await s.view(item.id)
  assert.equal(calls,2);resolve({request:item});await pending;assert.equal(s.busy.value,false)
})
test('successful transition clears old summary and prevents duplicate acceptance',async()=>{
  let writes=0
  const s=humanReviewActions(async(p,o)=>o?(writes++,{...item,status:'ACCEPTED'}):[item])
  await s.load();s.summary.value={request:item}
  await s.change(item.id,'ACCEPTED');await s.change(item.id,'ACCEPTED')
  assert.equal(writes,1);assert.equal(s.summary.value,null);assert.equal(s.reviews.value[0].status,'ACCEPTED')
})
test('failed refresh disables old rows and denied summaries never send requests',async()=>{
  let fail=false,calls=0
  const s=humanReviewActions(async()=>{calls++;if(fail)throw Error('503');return [{...item,summaryAccessible:false}]})
  await s.load();await s.view(item.id);assert.equal(calls,1)
  fail=true;await s.load();await s.change(item.id,'CLOSED');assert.equal(calls,2)
  assert.equal(s.ready.value,false);assert.match(s.error.value,/加载失败/)
})
