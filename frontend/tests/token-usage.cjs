const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),{transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/admin/tokenUsage.ts'),mod=new Module(file,module)
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
test('usage source distinguishes measured counts, unknown counts, local execution and unverified legacy data',()=>{
  const label=mod.exports.tokenUsageLabel
  assert.equal(label('PROVIDER_REPORTED'),'供应商返回值')
  assert.equal(label('NOT_REPORTED'),'未取得用量')
  assert.equal(label('NOT_APPLICABLE'),'非模型调用，不计模型用量')
  assert.equal(label('LEGACY_RECORDED'),'旧记录：来源未核实')
  for(const unknown of [undefined,null,'unrecognized'])assert.equal(label(unknown),'旧记录：来源未知')
})
