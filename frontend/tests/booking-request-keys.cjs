const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/booking/bookingRequestKeys.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {bookingRequestKeys}=mod.exports
test('uncertain response retains the same explicit retry key without sending requests',()=>{
  let count=0;const keys=bookingRequestKeys(()=>String(++count))
  assert.equal(keys.get('d','2026-10-09','上午','s'),keys.get('d','2026-10-09','上午','s'))
  assert.equal(count,1)
})
test('different doctor schedule or session does not reuse another booking key',()=>{
  let count=0;const keys=bookingRequestKeys(()=>String(++count))
  const values=[['d','date','上午','s'],['other','date','上午','s'],['d','next','上午','s'],['d','date','下午','s'],['d','date','上午','other']].map(x=>keys.get(...x))
  assert.equal(new Set(values).size,5)
})
test('successful booking releases only its own identity',()=>{
  let count=0;const keys=bookingRequestKeys(()=>String(++count))
  const first=keys.get('d','date','上午','s'),other=keys.get('other','date','上午','s')
  keys.complete('d','date','上午','s')
  assert.notEqual(keys.get('d','date','上午','s'),first)
  assert.equal(keys.get('other','date','上午','s'),other)
})
