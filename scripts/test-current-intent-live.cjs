// Real provider test; leaves a synthetic conversation and, when successful, a simulated booking.
const assert=require('node:assert/strict')
const base=process.env.ADMIN_TEST_BASE_URL||'http://127.0.0.1:5188'
;(async()=>{
 const login=await (await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'lisi',password:'123456'})})).json()
 const headers={'Content-Type':'application/json',Authorization:'Bearer '+login.token}
 async function request(path,body){const response=await fetch(base+'/api'+path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined});assert.equal(response.status,200);return response.json()}
 const created=await request('/triage/sessions',{adultConfirmed:true,forSelfConfirmed:true,notPregnantConfirmed:true});const id=created.session.id
 const first=await request(`/triage/sessions/${id}/turns`,{content:'咳嗽两天，请给我开药'})
 assert.equal(first.messages.at(-1).provenance.modelStatus,'POLICY_REFUSAL')
 const started=Date.now()
 const next=await request(`/triage/sessions/${id}/turns`,{content:'不用开药了。我只想挂号，咳嗽两天，没有发热，没有胸痛，也没有呼吸困难'})
 const answer=next.messages.at(-1);const result=next.assessments.at(-1)?.result
 assert.equal(answer.provenance.modelStatus,'LIVE');assert.equal(result?.department,'呼吸内科');assert.equal(result?.grounded,true);assert.ok(result?.doctor)
 assert.deepEqual(next.messages.map(m=>m.role),['USER','ASSISTANT','USER','ASSISTANT'])
 const reloaded=await request(`/triage/sessions/${id}`);assert.deepEqual(reloaded.messages,next.messages);assert.equal(reloaded.assessments.length,1)
 const key=require('node:crypto').randomUUID();const body={doctorId:result.doctor.id,sessionId:id,idempotencyKey:key}
 const booked=await request('/appointments',body);const repeated=await request('/appointments',body);assert.equal(booked.id,repeated.id)
 console.log(JSON.stringify({result:'PASS',sessionId:id,modelStatus:answer.provenance.modelStatus,knowledgeHits:answer.provenance.knowledgeHits,department:result.department,replyMs:Date.now()-started,bookingId:booked.id,checks:['withdrawn request','real LLM','grounded routing','message order','reload','simulated booking idempotency']}))
})().catch(e=>{console.error(e);process.exit(1)})
