// Uses the lisi demo account; synthetic health fields are restored after the test.
const {chromium}=require('playwright')
const assert=require('node:assert/strict')
const base=process.env.ADMIN_TEST_BASE_URL||'http://127.0.0.1:5188'
;(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 const page=await browser.newPage();let baseline,token
 try{
  await page.goto(base+'/?login=lisi&page=profile')
  await page.getByLabel('称呼',{exact:true}).waitFor()
  token=await page.evaluate(()=>localStorage.getItem('ai-hospital-token'))
  const headers={Authorization:'Bearer '+token}
  baseline=await (await page.request.get(base+'/api/patient/profile',{headers})).json()
  const save=page.getByRole('button',{name:'保存资料',exact:true})
  assert.equal(await save.isDisabled(),true)
  await page.getByLabel('称呼',{exact:true}).fill('资料流程测试')
  await page.getByLabel('过敏史（选填）',{exact:true}).fill('合成测试字段，非医学事实')
  await page.getByRole('checkbox').check();await save.click()
  await page.getByRole('status').filter({hasText:'资料已保存'}).waitFor()
  await page.reload();await page.getByLabel('称呼',{exact:true}).waitFor()
  assert.equal(await page.getByLabel('称呼',{exact:true}).inputValue(),'资料流程测试')
  const second=await browser.newPage();await second.goto(base+'/?login=lisi&page=profile');await second.getByLabel('称呼',{exact:true}).waitFor()
  await page.getByLabel('称呼',{exact:true}).fill('新版资料流程测试');await page.getByRole('checkbox').check();await save.click();await page.getByRole('status').filter({hasText:'资料已保存'}).waitFor()
  await second.getByLabel('称呼',{exact:true}).fill('旧版不应覆盖');await second.getByRole('checkbox').check();await second.getByRole('button',{name:'保存资料',exact:true}).click()
  await second.getByRole('alert').filter({hasText:'其他页面更新'}).waitFor()
  assert.equal(await second.getByLabel('称呼',{exact:true}).inputValue(),'旧版不应覆盖')
  assert.equal(await second.getByRole('button',{name:'保存资料',exact:true}).isDisabled(),true)
  await second.close()
  const admin=await (await page.request.post(base+'/api/auth/login',{data:{username:'admin',password:'123456'}})).json()
  assert.equal((await page.request.get(base+'/api/patient/profile',{headers:{Authorization:'Bearer '+admin.token}})).status(),403)
  console.log('PASS: profile save, reload, stale conflict, draft preserved, admin denied, confirmation required')
 }finally{
  if(baseline&&token){const headers={Authorization:'Bearer '+token};const current=await (await page.request.get(base+'/api/patient/profile',{headers})).json();const restored=await page.request.put(base+'/api/patient/profile',{headers,data:{...baseline,displayName:baseline.displayName||'李四',version:current.version,selfReportConfirmed:true}});assert.equal(restored.status(),200)}
  await browser.close()
 }
})().catch(e=>{console.error(e);process.exit(1)})
