// Synthetic isolated demo only. Simulates a lost confirmation response after the server commits.
const base=require('./local-test-target.cjs').localTestTarget(process.env.ADMIN_TEST_BASE_URL)
if(process.env.AI_MODE!=='demo'||process.env.AI_AGENT_MODE!=='OFF')throw Error('Confirm isolated demo/OFF configuration')
const {chromium}=require('playwright'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto')
;(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.ADMIN_TEST_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  const page=await browser.newPage();let department,doctor,doctorBody,admin
  const marker='Synthetic confirmation '+randomUUID()
  async function api(method,path,token,body,expected=200){
    const response=await page.request.fetch(base+'/api'+path,{method,
      headers:token?{Authorization:'Bearer '+token}:{},data:body})
    assert.equal(response.status(),expected,method+' '+path)
    return response.headers()['content-type']?.includes('application/json')?response.json():null
  }
  try{
    admin=(await api('POST','/auth/login',null,{username:'admin',password:'synthetic-browser-only'})).token
    department=await api('POST','/admin/catalog/departments',admin,{name:marker,enabled:true})
    const date=new Date();date.setUTCDate(date.getUTCDate()+2)
    doctorBody={name:marker,title:'Synthetic',departmentId:department.id,enabled:true,
      date:date.toISOString().slice(0,10),period:'上午',total:2,fee:0}
    doctor=await api('POST','/admin/catalog/doctors',admin,doctorBody)
    await page.goto(base+'/?demo=patient&page=booking')
    const card=page.locator('.doctor-card').filter({hasText:marker})
    await card.waitFor()
    let accepts=false,dialogs=0
    page.on('dialog',async dialog=>{
      dialogs++;assert.ok(dialog.message().includes(marker));assert.ok(dialog.message().includes('模拟'))
      accepts?await dialog.accept():await dialog.dismiss()
    })
    const cancelled=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.url().includes('/appointments/actions/'))
    await card.getByRole('button',{name:'模拟预约',exact:true}).click()
    assert.equal((await cancelled).status(),200)
    const patient=await page.evaluate(()=>localStorage.getItem('ai-hospital-token'))
    const bookings=()=>api('GET','/appointments',patient)
    assert.equal((await bookings()).filter(a=>a.doctor.id===doctor.id).length,0)
    const confirmIds=[];let committed
    await page.route('**/api/appointments/actions/*/confirm',async route=>{
      confirmIds.push(new URL(route.request().url()).pathname)
      const response=await route.fetch();assert.equal(response.status(),200)
      committed=await response.json();await route.abort('failed')
    })
    accepts=true
    await card.getByRole('button',{name:'模拟预约',exact:true}).click()
    await page.getByText('预约结果待确认：',{exact:false}).waitFor()
    assert.ok(committed?.id)
    assert.equal((await bookings()).filter(a=>a.doctor.id===doctor.id).length,1)
    await page.unroute('**/api/appointments/actions/*/confirm')
    page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/confirm'))confirmIds.push(new URL(request.url()).pathname)})
    await card.getByRole('button',{name:'模拟预约',exact:true}).click()
    await page.waitForURL(url=>url.searchParams.get('page')==='visits')
    assert.equal(dialogs,3);assert.equal(confirmIds.length,2);assert.equal(confirmIds[0],confirmIds[1])
    const receipts=(await bookings()).filter(a=>a.doctor.id===doctor.id)
    assert.equal(receipts.length,1);assert.equal(receipts[0].id,committed.id)
    const doctors=await api('GET','/admin/catalog/doctors',admin)
    assert.equal(doctors.find(d=>d.id===doctor.id).remaining,1)
    const other=(await api('POST','/auth/login',null,{username:'lisi',password:'synthetic-browser-only'})).token
    await api('POST',confirmIds[0].replace(/^\/api/,''),other,{confirmed:true},404)
    console.log(JSON.stringify({passed:true,mode:'SYNTHETIC_DEMO_ONLY',checks:[
      'server-detail-confirmation','cancel-no-deduction','commit-response-lost','same-action-explicit-retry',
      'single-receipt-single-deduction','history-navigation','other-patient-denied']}))
  }finally{
    try{
      if(doctor&&admin)await api('PUT','/admin/catalog/doctors/'+doctor.id,admin,{...doctorBody,enabled:false})
      if(department&&admin)await api('PUT','/admin/catalog/departments/'+department.id,admin,{name:marker,enabled:false})
    }finally{await browser.close()}
  }
})().catch(error=>{console.error(error);process.exitCode=1})
