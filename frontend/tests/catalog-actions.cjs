const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/admin/catalogActions.ts')
const mod=new Module(file,module);mod.paths=Module._nodeModulePaths(path.dirname(file))
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {catalogActions}=mod.exports

test('initial refresh reads once and enables edits only after a successful snapshot',async()=>{
  let reads=0,writes=0
  const state=catalogActions(async()=>{reads++})
  await state.execute(async()=>{writes++},'saved')
  assert.equal(writes,0);assert.equal(state.ready.value,false)
  await state.refresh()
  assert.equal(reads,1);assert.equal(state.ready.value,true)
})
test('initial directory failure is visible and explicit refresh recovers',async()=>{
  let fail=true
  const state=catalogActions(async()=>{if(fail)throw Error('HTTP 503')})
  await state.refresh()
  assert.equal(state.ready.value,false);assert.equal(state.busy.value,false)
  assert.match(state.error.value,/目录加载失败/)
  fail=false;await state.refresh()
  assert.equal(state.ready.value,true);assert.equal(state.error.value,'')
})
test('successful mutation with failed refresh stays successful and cannot be resubmitted',async()=>{
  let fail=false,writes=0
  const state=catalogActions(async()=>{if(fail)throw Error('read unavailable')})
  await state.refresh();fail=true
  await state.execute(async()=>{writes++},'doctor saved')
  assert.equal(state.notice.value,'doctor saved');assert.match(state.error.value,/操作已成功，但目录刷新失败/)
  assert.equal(state.ready.value,false)
  await state.execute(async()=>{writes++},'doctor saved')
  assert.equal(writes,1)
  fail=false;await state.refresh()
  assert.equal(state.ready.value,true);assert.equal(state.error.value,'')
})
test('uncertain write is not retried or reported as successful',async()=>{
  let writes=0,reads=0
  const state=catalogActions(async()=>{reads++})
  await state.refresh()
  const action=async()=>{writes++;throw Error('connection lost')}
  await state.execute(action,'saved');await state.execute(action,'saved')
  assert.equal(writes,1);assert.equal(reads,1)
  assert.equal(state.notice.value,'');assert.equal(state.ready.value,false)
  assert.match(state.error.value,/操作结果待核对/)
})
test('in-flight write blocks double submit and overlapping refresh',async()=>{
  let release,writes=0,reads=0
  const state=catalogActions(async()=>{reads++})
  await state.refresh()
  const pending=state.execute(()=>{writes++;return new Promise(resolve=>{release=resolve})},'saved')
  await state.execute(async()=>{writes++},'saved');await state.refresh()
  assert.equal(writes,1);assert.equal(reads,1)
  release();await pending
  assert.equal(reads,2);assert.equal(state.busy.value,false);assert.equal(state.notice.value,'saved')
})
