const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),{transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/invocationOrigin.ts'),mod=new Module(file,module)
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
test('origin labels distinguish model selection from execution and MCP from local workflow',()=>{
  const label=mod.exports.invocationOriginLabel
  assert.equal(label('WORKFLOW'),'后端流程选择');assert.equal(label('AGENT'),'Agent 选择（后端执行）')
  assert.equal(label('MCP'),'管理端 MCP 子集入口');assert.equal(label('ADMIN'),'管理员试运行')
  for(const unknown of [null,undefined,'UNKNOWN','forged'])assert.equal(label(unknown),'发起方未记录')
})
