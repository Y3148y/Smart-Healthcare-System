const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),ts=require('typescript')
const compiled=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../vite.config.ts'),'utf8'),{
  compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}
}).outputText
function config(env={}){
  const exports={}
  new Function('require','exports',compiled)(name=>{
    if(name==='vite')return {defineConfig:value=>value,loadEnv:()=>env}
    if(name==='@vitejs/plugin-vue')return {default:()=>({name:'synthetic plugin'})}
    throw Error('Unexpected dependency')
  },exports)
  return exports.default({mode:'test'})
}
test('default proxy remains 8080 and API/MCP share the explicitly configured backend',()=>{
  assert.equal(config().server.proxy['/api'].target,'http://127.0.0.1:8080')
  const result=config({VITE_API_PROXY_TARGET:'http://127.0.0.1:8081'})
  assert.equal(result.server.proxy['/api'].target,'http://127.0.0.1:8081')
  assert.equal(result.server.proxy['/mcp'].target,result.server.proxy['/api'].target)
})
test('development proxy rejects credential-bearing and non-HTTP targets',()=>{
  for(const target of ['file:///tmp/test','http://synthetic@localhost:8081','http://localhost:8081/?token=synthetic','http://localhost:8081/#synthetic'])
    assert.throws(()=>config({VITE_API_PROXY_TARGET:target}),/without credentials/)
})
