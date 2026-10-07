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
const parseMarkdown = context.exports.parseKnowledgeMarkdown
function canonicalFixture() {
  const metadata = fixture().metadata
  metadata.language = 'zh-CN'; metadata.contentKind = 'reviewed_summary'; metadata.evidenceUses = ['general_information']
  metadata.sources.push({...metadata.sources[0], sourceId:'second', url:'https://example.invalid/b'})
  const hash = text => createHash('sha256').update(text).digest('hex')
  const files = {'source.en.md':'First source\n', 'source.nlm.en.md':'Second source\n'}
  const provenance = {documentId:'fixture', approvalStatus:'PENDING_REVIEW', clinicalReviewStatus:'NOT_REVIEWED', metadata,
    sourceSnapshots:[{sourceId:'fixture',file:'source.en.md',sha256:hash(files['source.en.md'])},
      {sourceId:'second',file:'source.nlm.en.md',sha256:hash(files['source.nlm.en.md'])}]}
  files['provenance.json'] = JSON.stringify(provenance)
  files['document.zh-CN.md'] = '---\n'+JSON.stringify({schemaVersion:1,documentId:'fixture',title:'Fixture',
    approvalStatus:'PENDING_REVIEW',clinicalReviewStatus:'NOT_REVIEWED',provenanceFile:'provenance.json',
    provenanceSha256:hash(files['provenance.json'])})+'\n---\n# Knowledge\n\nBody only.'
  return files
}

test('canonical multi-source draft strips metadata and never grants permission', async () => {
  const files = canonicalFixture(), result = await parseMarkdown(files)
  assert.equal(result.body, '# Knowledge\n\nBody only.')
  assert.equal(result.metadata.sources.length, 2)
  assert.equal(result.metadata.contentKind, 'reviewed_summary')
  assert.equal(result.metadata.permissionEvidence, null)
})
test('canonical draft requires original source snapshots and bound provenance', async () => {
  for (const mutate of [f => delete f['source.nlm.en.md'], f => f['source.en.md'] += 'changed',
    f => f['provenance.json'] += ' ', f => delete f['provenance.json']]) {
    const files = canonicalFixture(); mutate(files)
    await assert.rejects(parseMarkdown(files))
  }
})
test('canonical importer does not infer approval or follow source paths', async () => {
  for (const change of [h => h.approvalStatus = 'APPROVED', h => h.clinicalReviewStatus = 'REVIEWED',
    h => h.provenanceFile = '../provenance.json', h => h.metadata = {}]) {
    const files = canonicalFixture()
    const parts = files['document.zh-CN.md'].split('\n')
    const header = JSON.parse(parts[1]); change(header); parts[1] = JSON.stringify(header)
    files['document.zh-CN.md'] = parts.join('\n')
    await assert.rejects(parseMarkdown(files))
  }
})

test('canonical provenance retains warnings but cannot grant permission', async () => {
  const files = canonicalFixture(), audit = JSON.parse(files['provenance.json'])
  audit.extractionWarnings = ['TABLE_REQUIRES_REVIEW']
  audit.metadata.permissionStatus = 'permitted'; audit.metadata.permissionEvidence = 'Fixture declaration only'
  files['provenance.json'] = JSON.stringify(audit)
  const parts = files['document.zh-CN.md'].split('\n'), header = JSON.parse(parts[1])
  header.provenanceSha256 = createHash('sha256').update(files['provenance.json']).digest('hex')
  parts[1] = JSON.stringify(header); files['document.zh-CN.md'] = parts.join('\n')
  const result = await parseMarkdown(files)
  assert.equal(result.warnings[0], 'TABLE_REQUIRES_REVIEW')
  assert.equal(result.metadata.permissionStatus, 'pending')
  assert.equal(result.metadata.permissionEvidence, null)
})
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
