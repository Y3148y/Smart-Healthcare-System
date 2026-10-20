const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript')
const compiled=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/api.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
function load(frames){
  const encoded=new TextEncoder().encode(frames.map(([event,data])=>`event:${event}\ndata:${JSON.stringify(data)}\n\n`).join(''))
  let index=0
  const context={exports:{},Headers,URLSearchParams,FormData,TextDecoder,window:{location:{search:''}},localStorage:{getItem:()=>null,removeItem:()=>{},setItem:()=>{}},
    fetch:async()=>new Response(new ReadableStream({pull(controller){if(index===encoded.length)controller.close();else controller.enqueue(encoded.slice(index,index+=1))}}),{headers:{'Content-Type':'text/event-stream'}})}
  vm.runInNewContext(compiled,context)
  return context.exports.streamResult
}
const delta=(seq,text,other={})=>['answer_delta',{messageId:'a',seq,text,checked:true,...other}]
const result=text=>['result',{messages:[{id:'a',role:'ASSISTANT',content:text}]}]
test('checked UTF8 deltas match final persisted reply across byte-sized network fragments',async()=>{
  const deltas=[]
  const answer=await load([delta(0,'中文'),delta(1,'😀'),result('中文😀')])('/fixture',{},()=>{},d=>deltas.push(d.text))
  assert.deepEqual(deltas,['中文','😀']);assert.equal(answer.messages[0].content,'中文😀')
})
test('unreviewed, reordered, switched or oversized deltas and mismatched final replies are rejected',async()=>{
  for(const frames of [[delta(0,'x',{checked:false}),result('x')],[delta(1,'x'),result('x')],
    [delta(0,'x'),delta(1,'y',{messageId:'b'}),result('xy')],[delta(0,'x'.repeat(129)),result('x')],
    [delta(0,'x'),result('different')],[delta(0,'x')],[result('x'),delta(0,'x')],[result('x'),result('x')]]) {
    await assert.rejects(()=>load(frames)('/fixture',{},()=>{}))
  }
})
