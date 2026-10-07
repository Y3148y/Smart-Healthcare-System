// Component regression with synthetic props; not a live LLM or hospital-data test.
const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const Module=require('node:module')
const {parse,compileScript}=require('@vue/compiler-sfc')
const {buildSync}=require('esbuild')
const {createSSRApp}=require('vue')
const {renderToString}=require('@vue/server-renderer')

const componentPath=path.resolve(__dirname,'../src/features/triage/components/AssessmentCard.vue')
const {descriptor}=parse(fs.readFileSync(componentPath,'utf8'))
const compiled=compileScript(descriptor,{id:'assessment-service-state',inlineTemplate:true})
const bundle=buildSync({stdin:{contents:compiled.content,loader:'ts',resolveDir:path.dirname(componentPath)},
  bundle:true,platform:'node',format:'cjs',write:false,external:['vue']})
const componentModule=new Module(componentPath,module)
componentModule.filename=componentPath
componentModule.paths=module.paths
componentModule._compile(bundle.outputFiles[0].text,componentPath)
const AssessmentCard=componentModule.exports.default

async function render(overrides={},props={}) {
  const result={sessionId:'test',riskLevel:'普通',confidence:72,department:'Test department',doctor:null,
    summary:'test',safetyTip:'test',evidence:[],tools:[],candidates:[],modelStatus:'DEMO',modelName:'',
    safetyAssessment:null,grounded:true,groundingMessage:'test',...overrides}
  const anchor={version:1,result,createdAt:'test',assistantMessageId:'test'}
  return renderToString(createSSRApp(AssessmentCard,{anchor,assessments:[anchor],bookingRunning:false,...props}))
}
test('specific service outcome is shown rather than a generic no-slots label',async()=>{
  for(const outcome of ['系统尚未配置该科室','该科室已停用','科室已配置，但暂无启用的医生',
    '科室有医生，但暂无今日或未来的模拟排班','科室有模拟排班，但暂无剩余号源']) {
    const html=await render({tools:[{tool:'department_search',success:true,outcome}]})
    assert.ok(html.includes(outcome))
    assert.ok(!html.includes('暂无模拟号源'))
  }
})
test('query failure is not labelled as a missing department',async()=>{
  const html=await render({tools:[{tool:'department_search',success:false,outcome:'工具执行失败'}]})
  assert.ok(html.includes('科室查询失败，暂无法确认服务状态'))
  assert.ok(!html.includes('系统尚未配置该科室'))
})
test('urgent disposition is not disguised as unavailable slots',async()=>{
  const html=await render({riskLevel:'尽快就医',tools:[{tool:'department_search',success:true,outcome:'test'}]})
  assert.ok(html.includes('当前已停止普通模拟预约'))
})
test('legacy records without service trace do not invent a department state',async()=>{
  assert.ok((await render()).includes('本版本未记录科室服务状态'))
})
test('missing evidence stays separate from catalogue capability',async()=>{
  const html=await render({grounded:false,tools:[{tool:'department_search',success:true,outcome:'test'}]})
  assert.ok(html.includes('医学依据不足，尚未提供具体模拟预约'))
})

test('current conversation refusal hides booking actions without removing the historical result',async()=>{
  const doctor={id:'test',name:'Test doctor'}
  const html=await render({doctor,summary:'Original assessment',candidates:[{department:'Test department',doctor,reason:'Original reason'},
    {department:'Another department',doctor,reason:'Second reason'}]},
    {currentSessionStatus:'待补充信息'})
  assert.ok(!/<button[^>]*>模拟预约/.test(html))
  assert.ok(html.includes('Test doctor'))
  assert.ok(html.includes('Original reason'))
  assert.ok(html.includes('当前会话暂不提供模拟预约'))
  assert.ok(!html.includes('请切换到最新分诊版本'))
})
test('completed conversation can show a booking action again',async()=>{
  const html=await render({doctor:{id:'test',name:'Test doctor'}},{currentSessionStatus:'已完成分诊'})
  assert.ok(/<button[^>]*>模拟预约 Test doctor/.test(html))
})
test('current session safety status cannot unlock an old normal assessment',async()=>{
  for(const status of ['紧急提示','建议尽快就医','处理中','待重试']) {
    const html=await render({doctor:{id:'test',name:'Test doctor'}},{currentSessionStatus:status})
    assert.ok(!/<button[^>]*>模拟预约/.test(html),status)
  }
})
