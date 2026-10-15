const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript')
const compiled=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/features/triage/recoverConversation.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const context={exports:{},setTimeout};vm.runInNewContext(compiled,context)
const recover=context.exports.recoverConversation
const view=(status,accepted=true,reply=false)=>({session:{status},messages:accepted?[{role:'USER',content:'问题'},...(reply?[{role:'ASSISTANT',content:'结果'}]:[])]:[]})

test('identical question text from another request cannot satisfy recovery identity',async()=>{
  const conversation={...view('处理中'),latestTurn:{requestId:'different',userMessageId:'u2',status:'RUNNING'}}
  conversation.messages[0].id='u2'
  const result=await recover(async()=>conversation,0,'问题',async()=>{},1,'original')
  assert.equal(result.saved,false)
})
test('an earlier completed turn is recovered by identity while a newer turn is running',async()=>{
  const conversation={session:{status:'处理中'},messages:[{id:'u1',role:'USER',content:'问题'},{id:'a1',role:'ASSISTANT',content:'结果'},
    {id:'u2',role:'USER',content:'下一题'}],latestTurn:{requestId:'newer',userMessageId:'u2',status:'RUNNING'}}
  const original={requestId:'original',userMessageId:'u1',assistantMessageId:'a1',status:'COMPLETED'}
  const result=await recover(async()=>conversation,0,'问题',async()=>{},1,'original',async()=>original)
  assert.equal(result.saved,true);assert.equal(result.processing,false);assert.equal(result.turn.assistantMessageId,'a1')
})
test('disconnect recovery reads until the accepted turn has a terminal result',async()=>{
  let calls=0,waits=0
  const result=await recover(async()=>++calls===1?view('处理中'):view('待补充信息',true,true),0,'问题',async()=>{waits++})
  assert.equal(calls,2);assert.equal(waits,1);assert.equal(result.saved,true);assert.equal(result.processing,false)
  assert.equal(result.conversation.messages.at(-1).role,'ASSISTANT')
})
test('processing recovery is bounded and never replays a write',async()=>{
  let reads=0,waits=0
  const result=await recover(async()=>{reads++;return view('处理中')},0,'问题',async()=>{waits++},3)
  assert.equal(reads,3);assert.equal(waits,2);assert.equal(result.processing,true)
})
test('unaccepted request and a different last question are not mistaken for saved input',async()=>{
  assert.equal((await recover(async()=>view('待补充信息',false),0,'问题',async()=>{})).saved,false)
  assert.equal((await recover(async()=>view('处理中'),0,'另一个问题',async()=>{})).saved,false)
})
test('transient read failure recovers without duplicate submission; retry terminal is preserved',async()=>{
  let reads=0
  const result=await recover(async()=>{if(++reads===1)throw Error('network');return view('待重试')},0,'问题',async()=>{},3)
  assert.equal(reads,2);assert.equal(result.saved,true);assert.equal(result.processing,false)
  assert.equal(result.conversation.session.status,'待重试')
})
