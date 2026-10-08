const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/triage/selectionVersion.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {selectionVersion}=mod.exports
test('slow earlier selection cannot replace a newer conversation',()=>{
  const state=selectionVersion(),old=state.next(),latest=state.next()
  assert.equal(state.isCurrent(old),false);assert.equal(state.isCurrent(latest),true)
})
test('new conversation invalidates a pending historical read',()=>{
  const state=selectionVersion(),pending=state.next();state.next()
  assert.equal(state.isCurrent(pending),false)
})
