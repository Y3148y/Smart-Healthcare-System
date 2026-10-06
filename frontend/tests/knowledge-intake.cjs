const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const {webcrypto, createHash} = require('node:crypto')
const source = fs.readFileSync(path.join(__dirname, '../src/features/admin/knowledgeIntake.ts'), 'utf8')
const compiled = ts.transpileModule(source, {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const context = {exports:{}, crypto:webcrypto, TextEncoder, URL, Uint8Array}
vm.runInNewContext(compiled, context)
const parse = context.exports.parseKnowledgeIntake
function fixture() {
  const body = '# Heading\n\nCondition and exception.'
  return {schemaVersion:1,reviewStatus:'PENDING',title:'Fixture',body,
    extraction:{contentSha256:createHash('sha256').update(body).digest('hex'),warnings:['TABLE_REQUIRES_REVIEW']},
    metadata:{schemaVersion:1,language:'und',contentKind:'source_extract',permissionStatus:'pending',permissionEvidence:null,
      sources:[{sourceId:'fixture',publisher:'Fixture',url:'https://example.invalid/a',fetchedAt:'2026-10-06T00:00:00Z',rawSha256:'a'.repeat(64)}],
      topics:[],population:[],exclusions:[],prerequisites:[],evidenceUses:[]}}
}
test('candidate retains body, warnings, pending permission and source provenance', async () => {
  const data = fixture(), result = await parse(JSON.stringify(data))
  assert.equal(result.body, data.body)
  assert.equal(result.warnings[0], 'TABLE_REQUIRES_REVIEW')
  assert.equal(result.metadata.sources[0].rawSha256, 'a'.repeat(64))
  assert.equal(result.metadata.permissionEvidence, null)
})
test('tampered body cannot be imported', async () => {
  const data = fixture(); data.body += ' changed'
  await assert.rejects(parse(JSON.stringify(data)), /哈希不一致/)
})
test('package cannot promote permission or use credential-bearing source URLs', async () => {
  const data = fixture(); data.metadata.permissionStatus = 'permitted'
  await assert.rejects(parse(JSON.stringify(data)), /已批准许可/)
  data.metadata.permissionStatus = 'pending'; data.metadata.sources[0].url = 'https://user:secret@example.invalid'
  await assert.rejects(parse(JSON.stringify(data)), /溯源字段/)
})
test('invalid dates, hashes and metadata arrays rejected', async () => {
  for (const change of [d => d.metadata.sources[0].fetchedAt = 'wrong', d => d.metadata.sources[0].rawSha256 = 'wrong', d => d.metadata.topics = [null]]) {
    const data = fixture(); change(data)
    await assert.rejects(parse(JSON.stringify(data)))
  }
})
