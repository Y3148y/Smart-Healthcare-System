const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path')

for(const file of ['frontend/package-lock.json','tools/knowledge-sync/package-lock.json']){
 test(`${file} pins public dependencies to the official HTTPS registry`,()=>{
  const lock=JSON.parse(fs.readFileSync(path.resolve(__dirname,'..',file),'utf8'))
  let resolved=0
  for(const [name,entry] of Object.entries(lock.packages)){
   if(!name)continue
   assert.equal(typeof entry.resolved,'string',`${name}: missing resolved source`)
   const url=new URL(entry.resolved)
   assert.equal(url.protocol,'https:');assert.equal(url.hostname,'registry.npmjs.org')
   assert.equal(url.username,'');assert.equal(url.password,'');assert.equal(url.port,'')
   assert.equal(url.search,'');assert.equal(url.hash,'')
   assert.match(entry.integrity||'',/^sha(?:1|256|384|512)-/)
   resolved++
  }
  assert.ok(resolved>0)
 })
}
