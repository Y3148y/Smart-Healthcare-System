// Explicit isolated demo target; no chat/model calls. Creates one empty synthetic session.
const {localTestTarget} = require('./local-test-target.cjs')

async function main() {
  const base=localTestTarget(process.env.CONTAINER_TEST_BASE_URL)
  const password=process.env.CONTAINER_TEST_PASSWORD
  if (!password) throw new Error('CONTAINER_TEST_PASSWORD must be supplied through the environment')
  const args=process.argv.slice(2), resumeIndex=args.indexOf('--resume')
  const resume=resumeIndex<0 ? null : args[resumeIndex+1]
  if (resumeIndex>=0 && !/^[a-f0-9-]{36}$/.test(resume||'')) throw new Error('Invalid synthetic session identity')
  let token
  async function request(path,body) {
    const response=await fetch(base+path,{method:body ? 'POST' : 'GET',redirect:'error',
      headers:{'Content-Type':'application/json',...(token ? {Authorization:`Bearer ${token}`} : {})},
      body:body ? JSON.stringify(body) : undefined,signal:AbortSignal.timeout(10000)})
    if (!response.ok) throw new Error(`Container smoke HTTP ${response.status}`)
    return response.json()
  }
  const health=await request('/api/health/ready')
  if (health.status!=='UP') throw new Error('Container database is not ready')
  const page=await fetch(base+'/',{redirect:'error',signal:AbortSignal.timeout(5000)})
  if (!page.ok || !(await page.text()).includes('<div id="app">')) throw new Error('SPA page unavailable')
  token=(await request('/api/auth/login',{username:'zhangsan',password})).token
  if (!token) throw new Error('Synthetic demo login failed')
  const conversation=resume ? await request(`/api/triage/sessions/${resume}`)
    : await request('/api/triage/sessions',{adultConfirmed:true,forSelfConfirmed:true,notPregnantConfirmed:true})
  const sessionId=conversation.session?.id
  if (!sessionId || resume && sessionId!==resume) throw new Error('Synthetic session did not survive restart')
  console.log(JSON.stringify({sessionId,resumed:!!resume,ready:true,spa:true,login:true}))
}
main().catch(error=>{console.error(error.message);process.exitCode=1})
