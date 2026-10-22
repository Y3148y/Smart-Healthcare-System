// Synthetic rendering contracts, not medical correctness or live-model tests.
const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {parse,compileScript}=require('@vue/compiler-sfc')
const {buildSync}=require('esbuild')
const {createSSRApp}=require('vue'),{renderToString}=require('@vue/server-renderer')
const file=path.resolve(__dirname,'../src/features/triage/components/AnswerSources.vue')
const {descriptor}=parse(fs.readFileSync(file,'utf8'))
const compiled=compileScript(descriptor,{id:'answer-sources-test',inlineTemplate:true})
const bundle=buildSync({stdin:{contents:compiled.content,loader:'ts',resolveDir:path.dirname(file)},bundle:true,platform:'node',format:'cjs',write:false,external:['vue']})
const mod=new Module(file,module);mod.filename=file;mod.paths=module.paths;mod._compile(bundle.outputFiles[0].text,file)
const reference=(id,title)=>({id,title,source:'https://example.org/source',excerpt:'Synthetic excerpt.',contentHash:'hash',applicability:'UNKNOWN',sectionPath:['Test topic','Test section']})
const valid={validationStatus:'REFERENCE_INTEGRITY_PASSED',supportReview:{status:'PASSED'},adoptedReferenceIds:['E1'],retrievedReferences:[reference('E1','Adopted fixture'),reference('E2','Unadopted fixture')]}
const render=evidence=>renderToString(createSSRApp(mod.exports.default,{evidence}))
test('only actually adopted checked references are shown',async()=>{
  const html=await render(valid)
  assert.ok(html.includes('Adopted fixture'));assert.ok(!html.includes('Unadopted fixture'))
  assert.ok(html.includes('Test topic / Test section'));assert.ok(html.includes('rel="noopener noreferrer"'))
})
test('blocked, unavailable, legacy and missing references do not invent adopted sources',async()=>{
  for(const value of [undefined,{}, {...valid,supportReview:{status:'REJECTED'}}, {...valid,supportReview:{status:'UNAVAILABLE'}},
    {...valid,validationStatus:'CONTENT_BLOCKED'}, {...valid,retrievedReferences:[]}, {...valid,adoptedReferenceIds:[]}])
    assert.ok(!(await render(value)).includes('Adopted fixture'))
})
test('duplicate reference identities are ambiguous rather than adopted twice',async()=>{
  assert.ok(!(await render({...valid,retrievedReferences:[reference('E1','Adopted fixture'),reference('E1','Duplicate')]})).includes('Adopted fixture'))
})
test('reference content is escaped and links reject invalid protocols and embedded credentials',async()=>{
  const ref={...reference('E1','<script>fixture</script>'),source:'javascript:alert(1) | https://user:secret@example.org/ | httpx://example.org/ | https://example.org/source'}
  const html=await render({...valid,retrievedReferences:[ref]})
  assert.ok(html.includes('&lt;script&gt;'));assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('user:secret'));assert.ok(!html.includes('javascript:'));assert.ok(!html.includes('httpx:'))
  assert.ok(html.includes('href="https://example.org/source"'))
})

test('source excerpts show distinct sources while retaining rejected model review',async()=>{
  const state={...valid,retrievalStatus:'MATCHED',validationStatus:'SOURCE_EXCERPT_VERIFIED',supportReview:{status:'REJECTED'},failure:{code:'OUTPUT_SUPPORT_REJECTED'}}
  const html=await render(state)
  assert.ok(html.includes('原文摘录的来源'));assert.ok(html.includes('不是模型核对通过'))
  assert.ok(html.includes('Adopted fixture'));assert.ok(!html.includes('Unadopted fixture'))
  for(const invalid of [{...state,validationStatus:'BLOCKED'},{...state,supportReview:{status:'UNAVAILABLE'}},{...state,failure:{code:'ANSWER_SOURCE_CHANGED'}}])
    assert.ok(!(await render(invalid)).includes('Adopted fixture'))
})

test('successful source selection is labelled as original text and still requires passed review',async()=>{
  const state={...valid,validationStatus:'SOURCE_QUOTE_VERIFIED'}
  assert.ok((await render(state)).includes('本次资料原文的来源'))
  assert.ok(!(await render({...state,supportReview:{status:'REJECTED'}})).includes('Adopted fixture'))
})
