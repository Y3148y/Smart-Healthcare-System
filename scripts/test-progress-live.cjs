// Real SSE and browser checks using synthetic lisi conversations, no booking or slot deduction.
const {chromium}=require('playwright'),assert=require('node:assert/strict')
const base=process.env.ADMIN_TEST_BASE_URL||'http://127.0.0.1:5188'
;(async()=>{
 const login=await(await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'lisi',password:'123456'})})).json()
 const headers={'Content-Type':'application/json',Authorization:'Bearer '+login.token}
 async function create(){const r=await fetch(base+'/api/triage/sessions',{method:'POST',headers,body:JSON.stringify({adultConfirmed:true,forSelfConfirmed:true,notPregnantConfirmed:true})});assert.equal(r.status,200);return(await r.json()).session.id}
 const id=await create(),started=Date.now()
 const response=await fetch(base+`/api/triage/sessions/${id}/turns/stream`,{method:'POST',headers,body:JSON.stringify({content:'咳嗽两天，没有胸痛，没有呼吸困难，我想挂号'})});assert.equal(response.status,200)
 const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',generation=false,firstStatus=null
 while(!generation){const c=await reader.read();assert.equal(c.done,false);buffer+=decoder.decode(c.value,{stream:true});if(firstStatus===null&&buffer.includes('event:status'))firstStatus=Date.now()-started;generation=buffer.includes('ANSWER_GENERATION')}
 const duplicate=await fetch(base+`/api/triage/sessions/${id}/turns/stream`,{method:'POST',headers,body:JSON.stringify({content:'重复请求不应写入'})});assert.equal(duplicate.status,409)
 await reader.cancel()
 let saved
 for(let i=0;i<60;i++){const r=await fetch(base+`/api/triage/sessions/${id}`,{headers});saved=await r.json();if(saved.messages.length===2)break;await new Promise(resolve=>setTimeout(resolve,1000))}
 assert.equal(saved.messages.length,2);assert.equal(saved.messages[1].provenance.modelStatus,'LIVE');assert.equal(saved.messages.filter(m=>m.role==='USER').length,1)
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try{
  const page=await browser.newPage();await page.goto(base+'/?login=lisi&page=triage');await page.locator('.chat-input textarea').waitFor()
  await page.evaluate(()=>{window.testStages=[];new MutationObserver(()=>{for(const e of document.querySelectorAll('.loading[role=status]')){const s=e.textContent;if(!window.testStages.includes(s))window.testStages.push(s)}}).observe(document.body,{childList:true,subtree:true,characterData:true})})
  await page.getByRole('checkbox').check();await page.locator('.chat-input textarea').fill('咳嗽两天，没有胸痛，没有呼吸困难，我想挂号')
  const streamResponse=page.waitForResponse(r=>r.url().endsWith('/turns/stream'))
  await page.getByRole('button',{name:'发送',exact:true}).click();assert.equal(await page.locator('.chat-input textarea').inputValue(),'')
  const streamed=await streamResponse;assert.equal(streamed.status(),200);const body=await streamed.text();assert.ok(body.includes('event:status'));assert.ok(body.includes('event:result'));assert.ok(body.includes('"modelStatus":"LIVE"'))
  await page.locator('.result-card').waitFor();const states=await page.evaluate(()=>window.testStages);assert.ok(states.some(s=>s.includes('生成并校验')))
  console.log(JSON.stringify({result:'PASS',firstStatusMs:firstStatus,disconnectRecoverySession:id,modelStatus:'LIVE',browserStages:states,checks:['real early status','same-session conflict','disconnect persisted answer','UTF-8 browser SSE','input cleared','validated result rendered']}))
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)})
