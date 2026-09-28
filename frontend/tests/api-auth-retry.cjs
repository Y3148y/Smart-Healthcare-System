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
    setItem: (key, value) => values.set(key, value)
  }
  const context = {
    exports: {}, fetch, localStorage: storage,
    window: { location: { search } },
    Headers, URLSearchParams, FormData
  }
  vm.runInNewContext(compiled, context, { filename: 'api.ts' })
  return { api: context.exports.api, storage }
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
