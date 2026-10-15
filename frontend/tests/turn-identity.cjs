const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/triage/turnIdentity.ts'),mod=new Module(file,module)
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {turnIdentity,clearTurnIdentity}=mod.exports
test('same unresolved request reuses its stored identity without persisting patient text',async()=>{
  const values=new Map();global.sessionStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}
  const first=await turnIdentity('session-a','Synthetic private question')
  assert.equal(await turnIdentity('session-a','Synthetic private question'),first)
  assert.notEqual(await turnIdentity('session-b','Synthetic private question'),first)
  assert.ok([...values.values()].every(v=>!v.includes('Synthetic private question')))
  clearTurnIdentity('session-a');assert.notEqual(await turnIdentity('session-a','Synthetic private question'),first)
})
test('changed question gets a new identity and corrupt/unavailable storage remains usable',async()=>{
  const values=new Map();global.sessionStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}
  const first=await turnIdentity('session','First question');assert.notEqual(await turnIdentity('session','Changed question'),first)
  values.set('ai-hospital-turn:session','broken');assert.match(await turnIdentity('session','First question'),/^[0-9a-f-]{36}$/)
  global.sessionStorage={getItem:()=>{throw Error('blocked')},setItem:()=>{throw Error('blocked')},removeItem:()=>{throw Error('blocked')}}
  assert.match(await turnIdentity('session','Question'),/^[0-9a-f-]{36}$/);assert.doesNotThrow(()=>clearTurnIdentity('session'))
})
