const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'api.ts'), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText

function loadApi(search, fetch, initialToken = 'expired-token') {
  const values = new Map([['ai-hospital-token', initialToken]])
  const storage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  }
  const context = {
    exports: {}, fetch, localStorage: storage,
    window: { location: { search } },
    Headers, URLSearchParams, FormData, TextDecoder
  }
  vm.runInNewContext(compiled, context, { filename: 'api.ts' })
  return { api: context.exports.api, streamResult:context.exports.streamResult, setToken:context.exports.setToken, clearSession:context.exports.clearSession, storage }
}

function response(status, body) {
  return { status, ok: status >= 200 && status < 300, json: async () => body }
}

test('expired demo token is refreshed and the protected request is retried', async () => {
  const requests = []
  const { api, storage } = loadApi('?demo=patient&page=triage', async (url, init) => {
    requests.push({ url, authorization: init.headers?.get?.('Authorization'), body: init.body })
    if (url === '/api/auth/login') return response(200, { token: 'fresh-token' })
    return init.headers.get('Authorization') === 'Bearer fresh-token'
      ? response(200, [{ id: 1 }])
      : response(401, { message: 'Unauthorized' })
  })

  assert.deepEqual(await api('/appointments'), [{ id: 1 }])
  assert.deepEqual(requests.map(request => request.url), [
    '/api/appointments', '/api/auth/login', '/api/appointments'
  ])
  assert.equal(requests[0].authorization, 'Bearer expired-token')
  assert.equal(JSON.parse(requests[1].body).username, 'zhangsan')
  assert.equal(requests[2].authorization, 'Bearer fresh-token')
  assert.equal(storage.getItem('ai-hospital-token'), 'fresh-token')
})

test('staggered concurrent 401 responses reuse the refreshed token', async () => {
  let loginCount = 0
  const { api } = loadApi('?demo=patient', async (url, init) => {
    if (url === '/api/auth/login') {
      loginCount++
      return response(200, { token: 'fresh-token' })
    }
    const authorization = init.headers.get('Authorization')
    if (authorization === 'Bearer expired-token') {
      if (url === '/api/triage/sessions') await new Promise(resolve => setTimeout(resolve, 15))
      return response(401, { message: 'Unauthorized' })
    }
    return response(200, { url })
  })

  const results = await Promise.all([api('/appointments'), api('/triage/sessions')])
  assert.deepEqual(results.map(result => result.url), ['/api/appointments', '/api/triage/sessions'])
  assert.equal(loginCount, 1)
})

test('normal login does not silently use a demo account', async () => {
  let loginCount = 0
  const { api } = loadApi('', async url => {
    if (url === '/api/auth/login') loginCount++
    return response(401, { message: 'Unauthorized' })
  })

  await assert.rejects(api('/appointments'), /Unauthorized/)
  assert.equal(loginCount, 0)
})

test('logout clears memory and only application login storage',async()=>{
  const authorizations=[]
  const {api,storage,clearSession}=loadApi('',async(url,init)=>{
    authorizations.push(init.headers.get('Authorization'));return response(200,{})
  })
  storage.setItem('unrelated-preference','keep')
  storage.setItem('ai-hospital-login','zhangsan')
  clearSession();await api('/appointments')
  assert.equal(storage.getItem('ai-hospital-token'),null)
  assert.equal(storage.getItem('ai-hospital-login'),null)
  assert.equal(storage.getItem('unrelated-preference'),'keep')
  assert.deepEqual(authorizations,[null])
})

test('late refresh cannot restore a logged-out session or replay its request',async()=>{
  let release,loginStarted,requests=0
  const started=new Promise(resolve=>{loginStarted=resolve})
  const {api,storage,clearSession}=loadApi('?demo=patient',async url=>{
    requests++
    if(url==='/api/auth/login'){loginStarted();return new Promise(resolve=>{release=resolve})}
    return response(401,{message:'Unauthorized'})
  })
  const pending=api('/appointments')
  await started;clearSession();release(response(200,{token:'late-token'}))
  await assert.rejects(pending,/登录状态已改变/)
  assert.equal(storage.getItem('ai-hospital-token'),null);assert.equal(requests,2)
  await assert.rejects(api('/appointments'),/Unauthorized/)
  assert.equal(requests,3,'logout must also stop implicit demo login')
})

test('late refresh cannot overwrite an explicitly established new login',async()=>{
  let release,loginStarted
  const started=new Promise(resolve=>{loginStarted=resolve})
  const {api,storage,setToken}=loadApi('?demo=patient',async url=>{
    if(url==='/api/auth/login'){loginStarted();return new Promise(resolve=>{release=resolve})}
    return response(401,{message:'Unauthorized'})
  })
  const pending=api('/appointments')
  await started;setToken('new-account-token');release(response(200,{token:'old-account-token'}))
  await assert.rejects(pending,/登录状态已改变/)
  assert.equal(storage.getItem('ai-hospital-token'),'new-account-token')
})

test('a response body arriving after logout is not delivered to the former view',async()=>{
  let release,bodyStarted
  const started=new Promise(resolve=>{bodyStarted=resolve})
  const {api,clearSession}=loadApi('',async()=>({status:200,ok:true,json:()=>{
    bodyStarted();return new Promise(resolve=>{release=resolve})
  }}))
  const pending=api('/appointments')
  await started;clearSession();release({privateFixture:true})
  await assert.rejects(pending,/登录状态已改变/)
})

test('stream results are rejected after the session changes during stage delivery',async()=>{
  const {streamResult,clearSession}=loadApi('',async()=>new Response(
    'event: status\ndata: {"stage":"QUERYING"}\n\nevent: result\ndata: {"privateFixture":true}\n\n',
    {headers:{'Content-Type':'text/event-stream'}}))
  await assert.rejects(streamResult('/triage/test',{},()=>clearSession()),/登录状态已改变/)
})

test('live proxy recovers a stale patient token', { skip: process.env.AI_HOSPITAL_LIVE !== '1' }, async () => {
  const requests = []
  const { api, storage } = loadApi('?demo=patient&page=triage', async (url, init) => {
    requests.push(url)
    return globalThis.fetch(`http://127.0.0.1:5188${url}`, init)
  })

  const appointments = await api('/appointments')
  const sessions = await api('/triage/sessions')
  assert.ok(Array.isArray(appointments))
  assert.ok(Array.isArray(sessions))
  assert.deepEqual(requests.slice(0, 3), [
    '/api/appointments', '/api/auth/login', '/api/appointments'
  ])
  assert.notEqual(storage.getItem('ai-hospital-token'), 'expired-token')
})
