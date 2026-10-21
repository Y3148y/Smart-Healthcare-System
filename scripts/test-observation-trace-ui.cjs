// Real Vue components and Chromium, synthetic intercepted APIs only; no running backend needed.
const assert=require('node:assert/strict'),path=require('node:path'),{pathToFileURL}=require('node:url')
const {createRequire}=require('node:module'),{chromium}=require('playwright')
const frontend=path.resolve(__dirname,'../frontend'),fromFrontend=createRequire(path.join(frontend,'package.json'))
const trace='12345678-1234-1234-1234-123456789abc'
const call={id:'fixture-call',time:'2026-10-18T10:00:00',purpose:'Synthetic generation',model:'fixture',inputTokens:null,outputTokens:null,
  elapsedMs:12,success:true,tools:[],traceId:trace,tokenUsageSource:'NOT_REPORTED',initiator:'WORKFLOW'}
const harness=`import {createApp,h,ref} from 'vue';
import Observations from '/src/features/triage/components/ConversationObservations.vue';
import Execution from '/src/features/triage/components/AnswerExecution.vue';
import Runtime from '/src/features/admin/RuntimeObservePage.vue';
import Sources from '/src/features/triage/components/AnswerSources.vue';
import Assessment from '/src/features/triage/components/AssessmentCard.vue';
const source={id:'E1',title:'Synthetic excerpt source',source:'https://example.invalid/source',excerpt:'Only when fixture condition holds. <script>window.EXCERPT_POISONED=true</script>',contentHash:'a'.repeat(64),applicability:'UNKNOWN',sectionPath:['Fixture','Only when fixture condition holds']};
const excerptEvidence={retrievalStatus:'MATCHED',validationStatus:'SOURCE_EXCERPT_VERIFIED',supportReview:{status:'REJECTED',issueCodes:['WRONG_REFERENCE'],elapsedMs:1},failure:{code:'OUTPUT_SUPPORT_REJECTED',causeType:'fixture',httpStatus:null},adoptedReferenceIds:['E1'],retrievedReferences:[source]};
const assessment={version:1,assistantMessageId:'fixture-answer',createdAt:'2026-10-21',result:{sessionId:'fixture',modelStatus:'SOURCE_EXCERPT',modelName:'fixture',riskLevel:'普通',grounded:true,confidence:70,summary:'Synthetic quotation only.',safetyTip:'Fixture',evidence:[],tools:[],candidates:[],safetyAssessment:null,answerEvidence:excerptEvidence,doctor:{id:'fixture-doctor',name:'Fixture Doctor',department:'Fixture',title:'Fixture',date:'2026-10-21',period:'AM',remaining:1,total:1,fee:0}}};
createApp({setup(){const session=ref('one'),withdrawn=ref(false);return()=>h('main',[
 h('button',{onClick:()=>session.value=session.value==='one'?'two':'one'},'Switch synthetic session'),
 h(Observations,{sessionId:session.value}),
 h(Execution,{meta:{modelStatus:'LIVE',knowledgeHits:0,localToolCalls:0,toolFailures:0,turnTrace:{executions:[
 {kind:'ROUTE',name:'GUIDANCE',status:'NEEDS_INFORMATION',elapsedMs:0,initiator:'WORKFLOW'},
 {kind:'INTENT_ROUTE',name:'FIND_DOCTOR',status:'department_search',elapsedMs:0,initiator:'AGENT'}]}}}),
 h('button',{onClick:()=>withdrawn.value=!withdrawn.value},'Toggle synthetic source validity'),
 h('section',{id:'source-excerpt-fixture'},[h(Sources,{evidence:withdrawn.value?{...excerptEvidence,validationStatus:'BLOCKED',failure:{code:'ANSWER_SOURCE_CHANGED'}}:excerptEvidence})]),
 h(Assessment,{anchor:assessment,assessments:[assessment],currentSessionStatus:'已完成分诊',bookingRunning:false}),
 h(Runtime)])}}).mount('#app');`

async function main(){
 const {createServer}=await import(pathToFileURL(fromFrontend.resolve('vite')).href)
 const {default:vue}=await import(pathToFileURL(fromFrontend.resolve('@vitejs/plugin-vue')).href)
 const server=await createServer({configFile:false,root:frontend,cacheDir:path.resolve(__dirname,'../.codex-vite-cache/ui-observation-probe'),
  logLevel:'error',server:{host:'127.0.0.1',port:0},plugins:[vue(),{
   name:'synthetic-component-probe',resolveId(id){if(id==='virtual:component-probe')return '\0virtual:component-probe'},
   load(id){if(id==='\0virtual:component-probe')return harness},
   configureServer(s){s.middlewares.use('/__probe',(_req,res)=>{res.setHeader('Content-Type','text/html; charset=utf-8');res.end('<div id="app"></div><script type="module" src="/@id/__x00__virtual:component-probe"></script>')})}
  }]})
 let browser
 try{
  await server.listen();const base=`http://127.0.0.1:${server.httpServer.address().port}`
  browser=await chromium.launch({headless:true,executablePath:process.env.ADMIN_TEST_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  const page=await browser.newPage({viewport:{width:1440,height:900}}),errors=[],unexpected=[]
  page.on('pageerror',error=>errors.push(error.name))
  let observationRequests=0,failObservation=false,failTrace=false,malformedCalls=false
  await page.route('**/*',async route=>{
   const url=new URL(route.request().url())
   if(url.origin!==base){unexpected.push('NON_LOCAL_REQUEST');return route.abort()}
   if(!url.pathname.startsWith('/api/'))return route.continue()
   if(route.request().method()!=='GET'){unexpected.push('NON_GET_API');return route.abort()}
   const reply=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
   if(/^\/api\/triage\/sessions\/(one|two)\/observations$/.test(url.pathname)){
    observationRequests++;if(failObservation)return reply({message:'PRIVATE_RAW_ERROR'},503)
    const id=url.pathname.split('/')[4]
    return reply({sessionId:id,stateVersion:id==='one'?1:0,status:id==='one'?'COMPLETED_OBSERVATIONS':'NO_COMPLETED_SNAPSHOT',clinicalReview:'NOT_REVIEWED',
     observations:id==='one'?[{symptom:'Synthetic historical observation',subject:'SELF',temporality:'CURRENT',assertion:'AFFIRMED',sourceRefs:[{messageId:'u1',quote:'<script>window.POISONED=true</script>'}]}]:[],unresolvedConflicts:[]})
   }
   if(url.pathname==='/api/admin/calls'){
    if(url.searchParams.has('traceId'))return failTrace?reply({message:'PRIVATE_RAW_ERROR'},503):reply([call,{...call,id:'fixture-review',purpose:'Synthetic review'}])
    return reply(malformedCalls?[{...call,tools:null}]:[call])
   }
   if(url.pathname==='/api/admin/ai-runtime')return reply({configured:false,mode:'demo',modelName:'',detail:'Synthetic only'})
   if(url.pathname==='/api/admin/knowledge/runtime')return reply({})
   if(url.pathname==='/api/admin/knowledge/retrieval-events')return reply([])
   unexpected.push('UNEXPECTED_API');return reply({},404)
  })
  await page.goto(base+'/__probe');await page.getByRole('heading',{name:'AI 运行观测'}).waitFor()
  const observations=page.locator('details.observation-records'),summary=observations.locator(':scope > summary')
  assert.equal(observationRequests,0)
  await summary.click();await observations.getByText('Synthetic historical observation',{exact:true}).waitFor()
  assert.match(await observations.innerText(),/当轮标记为当前/)
  assert.equal(await page.evaluate(()=>window.POISONED),undefined)
  assert.equal(await observations.locator('script').count(),0)
  const fits=await observations.locator('.observation-body').evaluate(el=>el.clientHeight<=Math.min(224,innerHeight*.32)+2)
  assert.ok(fits)
  await page.getByRole('region',{name:'本轮调用详情'}).getByText('后端工作流分流：',{exact:false}).waitFor()
  const patientExecution=page.getByRole('region',{name:'本轮调用详情'})
  await patientExecution.getByText(/Agent 结构化意图：FIND_DOCTOR；本轮授权工具：department_search/).waitFor()
  const excerpt=page.locator('#source-excerpt-fixture')
  await excerpt.getByText(/原文摘录的来源/).click()
  assert.match(await excerpt.innerText(),/不是模型核对通过.*结论/)
  assert.match(await excerpt.innerText(),/Only when fixture condition holds/)
  assert.equal(await excerpt.locator('script').count(),0)
  assert.equal(await page.evaluate(()=>window.EXCERPT_POISONED),undefined)
  assert.equal(await page.locator('.result-card').getByRole('button',{name:/模拟预约/}).count(),0)
  await page.getByRole('button',{name:'Toggle synthetic source validity'}).click()
  assert.equal(await excerpt.locator('details.answer-sources').count(),0)
  await page.getByRole('button',{name:'Switch synthetic session'}).click()
  await page.waitForFunction(()=>!document.querySelector('details.observation-records').open)
  assert.equal(observationRequests,1)
  failObservation=true;await summary.click();await observations.getByRole('status').waitFor()
  assert.match(await observations.innerText(),/不表示没有症状/)
  assert.doesNotMatch(await observations.innerText(),/Synthetic historical observation|PRIVATE_RAW_ERROR/)
  failObservation=false;await observations.getByRole('button',{name:'重试',exact:true}).click()
  await observations.getByText('尚无已完成的AI观察记录；不表示没有症状。',{exact:true}).waitFor()
  await page.getByRole('button',{name:'查看本轮链路',exact:true}).click()
  const chain=page.getByRole('region',{name:'本轮技术调用链'})
  await chain.getByText('Synthetic review',{exact:true}).waitFor()
  assert.match(await chain.innerText(),/未取得|未报告|未记录/)
  failTrace=true;await page.getByRole('button',{name:'查看本轮链路',exact:true}).click()
  await chain.getByText('本轮技术记录暂不可用，请重试；不会展示其他轮次的旧结果。',{exact:true}).waitFor()
  assert.doesNotMatch(await chain.innerText(),/Synthetic review|PRIVATE_RAW_ERROR/)
  malformedCalls=true;await page.getByRole('button',{name:'刷新状态与记录',exact:true}).click()
  await page.getByText('调用记录暂不可用，请刷新重试；不代表没有记录。',{exact:true}).waitFor()
  assert.equal(await page.getByRole('button',{name:'查看本轮链路',exact:true}).count(),0)
  malformedCalls=false;await page.getByRole('button',{name:'刷新状态与记录',exact:true}).click()
  await page.getByRole('button',{name:'查看本轮链路',exact:true}).waitFor()
  assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[])
  console.log('PASS: Chromium actual components; owner/history, text-only sources, session reset, source-excerpt label/context/escaping/withdrawal and no booking, workflow/Agent disclosure, trace failure/recovery; synthetic GET APIs only')
 }finally{if(browser)await browser.close();await server.close()}
}
main().catch(error=>{console.error('Component browser probe failed:',error.name);process.exitCode=1})
