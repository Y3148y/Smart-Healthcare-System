// Synthetic conversation on the lisi demo account. The created request is closed, not erased.
const {chromium}=require('playwright')
const assert=require('node:assert/strict')
const base=process.env.ADMIN_TEST_BASE_URL||'http://127.0.0.1:5188'
;(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 const page=await browser.newPage();let review,adminHeaders
 try{
  const patient=await (await page.request.post(base+'/api/auth/login',{data:{username:'lisi',password:'123456'}})).json()
  const headers={Authorization:'Bearer '+patient.token}
  const created=await page.request.post(base+'/api/triage/sessions',{headers,data:{adultConfirmed:true,forSelfConfirmed:true,notPregnantConfirmed:true}});assert.equal(created.status(),200)
  const session=(await created.json()).session.id
  const turn=await page.request.post(base+`/api/triage/sessions/${session}/turns`,{headers,timeout:120000,data:{content:'咳嗽两天，没有胸痛，也没有呼吸困难，想请人工导诊核对就诊方向。'}})
  assert.equal(turn.status(),200)
  const conversation=await turn.json();const assistant=conversation.messages.filter(m=>m.role==='ASSISTANT').at(-1)
  assert.ok(assistant?.content)
  console.log('Model status:',assistant.provenance?.modelStatus||'not-reported')
  const reason='人工导诊流程测试-'+Date.now()
  review=await (await page.request.post(base+`/api/triage/sessions/${session}/human-review`,{headers,data:{reason}})).json()
  assert.ok(review.id)
  await page.goto(base+'/?login=lisi&page=visits')
  const patientRow=page.getByRole('row').filter({hasText:reason});await patientRow.waitFor();assert.ok((await patientRow.innerText()).includes('等待处理'))
  const other=await (await page.request.post(base+'/api/auth/login',{data:{username:'zhangsan',password:'123456'}})).json()
  const otherReviews=await (await page.request.get(base+'/api/patient/human-reviews',{headers:{Authorization:'Bearer '+other.token}})).json()
  assert.equal(otherReviews.some(r=>r.id===review.id),false)
  assert.equal((await page.request.get(base+`/api/admin/human-reviews/${review.id}/summary`,{headers})).status(),403)
  const admin=await (await page.request.post(base+'/api/auth/login',{data:{username:'admin',password:'123456'}})).json();adminHeaders={Authorization:'Bearer '+admin.token}
  await page.goto(base+'/?demo=admin&page=admin&adminPage=reviews')
  const row=page.getByRole('row').filter({hasText:reason});await row.waitFor();await row.getByRole('button',{name:'查看摘要',exact:true}).click()
  await page.getByRole('heading',{name:'申请关联会话摘要'}).waitFor()
  assert.ok((await page.locator('.review-summary').innerText()).includes('咳嗽两天'))
  await row.getByRole('button',{name:'受理',exact:true}).click();await page.getByRole('status').filter({hasText:'申请状态已更新'}).waitFor();assert.ok((await row.innerText()).includes('已受理'))
  await row.getByRole('button',{name:'关闭',exact:true}).click();await page.waitForFunction(reason=>Array.from(document.querySelectorAll('tr')).some(r=>r.textContent.includes(reason)&&r.textContent.includes('已关闭')),reason)
  await page.goto(base+'/?login=lisi&page=visits');await patientRow.waitFor();assert.ok((await patientRow.innerText()).includes('已关闭'))
  console.log('PASS: patient request status, admin summary, accept then close, patient isolation, summary forbidden to patient')
 }finally{
  if(review?.id&&adminHeaders){const response=await page.request.patch(base+`/api/admin/human-reviews/${review.id}`,{headers:adminHeaders,data:{status:'CLOSED'}});assert.ok([200,409].includes(response.status()))}
  await browser.close()
 }
})().catch(e=>{console.error(e);process.exit(1)})
