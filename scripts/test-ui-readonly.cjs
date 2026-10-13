// Optional live-browser smoke test. Requires Node 22, a Chromium executable,
// and the local frontend/backend. It only logs in with demo accounts and reads pages.
const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')

const base = process.env.UI_TEST_BASE_URL || 'http://127.0.0.1:5188'
const browser = process.env.UI_TEST_BROWSER || (process.platform === 'win32'
  ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : 'chromium')
const endpoint = new URL(base)
assert.ok(endpoint.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname),
  'UI_TEST_BASE_URL must be a loopback HTTP address; demo credentials must never be sent elsewhere')

async function waitUntil(check, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    try { const value = await check(); if (value) return value } catch { /* navigation in progress */ }
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  throw new Error(`Timed out waiting for UI state: ${check.toString().slice(0, 160)}`)
}

async function connectPage(debugPort) {
  const response = await fetch(`http://127.0.0.1:${debugPort}/json/new?about:blank`, { method: 'PUT' })
  if (!response.ok) throw new Error(`Cannot create browser tab: HTTP ${response.status}`)
  const tab = await response.json()
  const socket = new WebSocket(tab.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', reject, { once: true })
  })
  let nextId = 0
  const pending = new Map()
  const pageErrors = []
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    if (message.method === 'Runtime.exceptionThrown') pageErrors.push('Uncaught page exception')
    if (!message.id || !pending.has(message.id)) return
    const { resolve, reject } = pending.get(message.id)
    pending.delete(message.id)
    message.error ? reject(new Error(message.error.message)) : resolve(message.result)
  })
  function send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++nextId
      pending.set(id, { resolve, reject })
      socket.send(JSON.stringify({ id, method, params }))
    })
  }
  await send('Page.enable')
  await send('Runtime.enable')
  async function evaluate(expression) {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error('Browser evaluation failed')
    return result.result.value
  }
  return { socket, send, evaluate, pageErrors }
}

async function run() {
  assert.ok(typeof WebSocket === 'function', 'Node 22 or newer is required for the browser smoke test')
  const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'ai-ui-readonly-'))
  const child = spawn(browser, [
    '--headless', '--no-sandbox', '--disable-gpu', '--disable-software-rasterizer',
    '--no-first-run', '--disable-extensions', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, 'about:blank'
  ], { stdio: 'ignore' })
  let launchError
  child.on('error', error => { launchError = error })
  const exited = new Promise(resolve => child.once('exit', resolve))
  let page
  try {
    const port = await waitUntil(async () => {
      if (launchError) return 'LAUNCH_FAILED'
      const content = await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')
      return Number(content.split(/\r?\n/)[0]) || null
    })
    if (port === 'LAUNCH_FAILED') throw new Error(`Browser launch failed: ${launchError.code || 'unknown'}`)
    page = await connectPage(port)
    await page.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
      const original = window.fetch.bind(window)
      const session = { id: 'ui-smoke-history', title: 'Synthetic prior conversation',
        preview: 'Fixture only', status: '待补充信息',
        createdAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z' }
      const response = data => Promise.resolve(new Response(JSON.stringify(data),
        { status: 200, headers: { 'Content-Type': 'application/json' } }))
      window.__uiSmokeHistoryReads = 0
      window.fetch = (input, init) => {
        if ((!init?.method || init.method === 'GET') &&
            String(input) === '/api/triage/sessions') return response([session])
        if ((!init?.method || init.method === 'GET') &&
            String(input) === '/api/triage/sessions/ui-smoke-history') {
          window.__uiSmokeHistoryReads++
          return response({ session, assessments: [], humanReview: null, messages: [
            { id: 'fixture-user', role: 'USER', content: 'Synthetic historical question', createdAt: session.createdAt },
            { id: 'fixture-assistant', role: 'ASSISTANT', content: 'Synthetic historical reply',
              createdAt: session.updatedAt, provenance: { modelStatus: 'DEMO', knowledgeHits: 0,
                localToolCalls: 1, toolFailures: 0,
                turnTrace: { executions: [
                  {kind:'TOOL',name:'medical_knowledge_retrieve',status:'SUCCEEDED',elapsedMs:12},
                  {kind:'RETRIEVAL',name:'LOCAL_BM25_UNRERANKED',status:'UNAVAILABLE / DISABLED_BY_CONFIGURATION',elapsedMs:11,rrfUsed:true,rerankEnabled:false,rerankUsed:false,selectedCount:1}
                ]},
                answerEvidence: {retrievalStatus:'RETRIEVED',validationStatus:'NOT_RUN',adoptedReferenceIds:[],
                  retrievedReferences:[{id:'E1',title:'Synthetic document',excerpt:'Synthetic excerpt only',sectionPath:['Synthetic section']}]} } }
          ] })
        }
        return original(input, init)
      }
    })()` })
    async function open(route, marker) {
      await page.send('Page.navigate', { url: new URL(route, base).href })
      await waitUntil(() => page.evaluate(`document.body && document.body.innerText.includes(${JSON.stringify(marker)})`))
    }
    async function click(selector) {
      assert.ok(await page.evaluate(`Boolean(document.querySelector(${JSON.stringify(selector)}))`), `Missing ${selector}`)
      await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click(); true`)
    }
    await open('/?demo=patient&page=triage', 'AI 门诊预问诊')
    await waitUntil(() => page.evaluate('document.querySelector(".conversation-list .session-button b")?.innerText === "Synthetic prior conversation"'))
    await click('.conversation-list .session-button')
    await waitUntil(() => page.evaluate('document.querySelector(".chat-scroll")?.innerText.includes("Synthetic historical reply")'))
    assert.equal(await page.evaluate('window.__uiSmokeHistoryReads'), 1,
      'Historical conversation must be loaded from the read endpoint')
    await click('.answer-provenance > summary')
    assert.ok(await page.evaluate(`document.querySelector('.execution').innerText.includes('rerank 已开启：否') && document.querySelector('.execution').innerText.includes('成功使用 rerank：否') && document.querySelector('.execution').innerText.includes('medical_knowledge_retrieve')`), 'Execution facts must distinguish configuration and actual rerank use')
    await click('.execution details > summary')
    assert.ok(await page.evaluate(`document.querySelector('.execution').innerText.includes('Synthetic excerpt only') && document.querySelector('.execution').innerText.includes('不代表已引用')`), 'Retrieved excerpts must not claim adoption')
    await click('.conversation-list .side-title button')
    await waitUntil(() => page.evaluate('!document.querySelector(".chat-scroll")?.innerText.includes("Synthetic historical reply")'))
    await click('a[href*="page=visits"]')
    await waitUntil(() => page.evaluate('document.querySelector("h1")?.innerText === "我的就诊记录"'))
    assert.equal(await page.evaluate('document.querySelector(".triage-stage-note")?.innerText || null'), null,
      'Patient resources failed to load; a visible visits heading alone is insufficient')
    await open('/?demo=admin&page=admin&adminPage=dashboard', '功能导航')
    await click('.admin-side a[href*="adminPage=knowledge"]')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-title h2")?.innerText === "医学知识库"'))
    await click('.admin-title button.primary') // opens the intake form; does not submit it
    await waitUntil(() => page.evaluate('Boolean(document.querySelector(".admin-title button.primary")?.innerText.includes("收起录入"))'))
    await click('.admin-title button.primary')
    await waitUntil(() => page.evaluate('Boolean(document.querySelector(".table-card.admin-scroll tbody tr")) && !document.querySelector("tbody td.empty")?.innerText.includes("正在加载")'))
    assert.equal(await page.evaluate('document.querySelector(".admin-workspace [role=alert]")?.innerText || null'), null,
      'Knowledge APIs failed; an empty table is not proof of a successful load')
    await page.evaluate(`(() => {
      const original = window.fetch.bind(window)
      window.__uiSmokeOriginalFetch = original
      window.fetch = (input, init) => String(input).includes('/api/admin/knowledge/runtime')
        ? Promise.resolve(new Response(JSON.stringify({ message: 'Synthetic read-only outage' }),
            { status: 503, headers: { 'Content-Type': 'application/json' } }))
        : original(input, init)
      return true
    })()`)
    await click('.admin-title button.mini') // fixture only: prove the page exposes a dependency failure
    await waitUntil(() => page.evaluate('document.querySelector(".admin-workspace [role=alert]")?.innerText.includes("Synthetic read-only outage")'))
    await page.evaluate(`(() => {
      const original = window.__uiSmokeOriginalFetch
      window.__uiSmokeRecovery = { started: 0, pending: 0 }
      window.fetch = (...args) => {
        window.__uiSmokeRecovery.started++
        window.__uiSmokeRecovery.pending++
        return original(...args).finally(() => window.__uiSmokeRecovery.pending--)
      }
      return true
    })()`)
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('window.__uiSmokeRecovery?.started >= 2 && window.__uiSmokeRecovery.pending === 0 && !document.querySelector(".admin-workspace [role=alert]") && Boolean(document.querySelector(".table-card.admin-scroll tbody tr"))'))
    const hasDocuments = await page.evaluate('Boolean(document.querySelector(".table-card.admin-scroll tbody tr button.mini"))')
    if (hasDocuments) {
      await click('.table-card.admin-scroll tbody tr button.mini') // GET detail only; never approve
      await waitUntil(() => page.evaluate(`Boolean(document.querySelector('[aria-label="文档详情"]'))`))
      await click('[aria-label="文档详情"] summary')
      assert.ok(await page.evaluate(`document.querySelector('[aria-label="文档详情"] pre')?.innerText.length > 0`))
      assert.equal(await page.evaluate('document.querySelector(".admin-workspace [role=alert]")?.innerText || null'), null,
        'Document details or provenance failed to load')
    }
    await page.evaluate(`(() => {
      const original = window.__uiSmokeOriginalFetch
      const doc = { id: 'ui-smoke-pending', title: 'Synthetic pending approval fixture',
        body: '# Fixture\\n\\nNon-medical text.', chunks: 1, status: 'PENDING_REVIEW',
        updatedAt: '2026-10-12T00:00:00Z' }
      const reply = data => Promise.resolve(new Response(JSON.stringify(data),
        { status: 200, headers: { 'Content-Type': 'application/json' } }))
      window.__uiSmokePaths = []
      window.__uiSmokePermission = 'pending'
      window.__uiSmokeApprovalCalls = 0
      window.fetch = (input, init) => {
        const url = String(input)
        if (url.includes('ui-smoke-pending')) window.__uiSmokePaths.push(url)
        if (url === '/api/admin/knowledge/ui-smoke-pending/approve' && init?.method === 'POST') {
          if (window.__uiSmokePermission !== 'permitted') throw Error('Fixture approval requires permitted usage')
          window.__uiSmokeApprovalCalls++
          doc.status = 'READY'
          return reply(doc)
        }
        if (!init?.method || init.method === 'GET') {
          if (url === '/api/admin/knowledge') return reply([doc])
          if (url === '/api/admin/knowledge/ui-smoke-pending/details') return reply({ document: doc,
            source: 'Synthetic browser fixture', segments: [{ title: doc.title, source: 'fixture',
              excerpt: 'Non-medical text.', score: 1 }], indexStatus: 'NOT_APPROVED',
            indexedChunks: 0, indexNote: 'Browser fixture only' })
          if (url === '/api/admin/knowledge/ui-smoke-pending/metadata') return reply({ status: 'DECLARED',
            clinicalReview: 'NOT_REVIEWED', metadata: { language: 'zh-CN', contentKind: 'reviewed_summary',
              topics: [], population: [], exclusions: [], prerequisites: [], evidenceUses: [],
              permissionStatus: window.__uiSmokePermission,
              permissionEvidence: window.__uiSmokePermission === 'permitted' ? 'Synthetic fixture declaration' : null,
              sources: [] } })
          if (url === '/api/admin/knowledge/ui-smoke-pending/chunks') return reply([])
        }
        if (url.includes('/ui-smoke-pending/') && init?.method !== 'GET')
          throw Error('Synthetic knowledge write must never leave the browser fixture')
        return original(input, init)
      }
      return true
    })()`)
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('document.querySelector(".table-card.admin-scroll tbody tr b")?.innerText === "Synthetic pending approval fixture"'))
    await click('.table-card.admin-scroll tbody tr button.mini')
    try {
      await waitUntil(() => page.evaluate(`document.querySelector('[aria-label="来源与溯源"]')?.innerText.includes('使用许可尚未确认')`))
    } catch (failure) {
      const state = await page.evaluate(`JSON.stringify({ detail: Boolean(document.querySelector('[aria-label="文档详情"]')),
        panel: Boolean(document.querySelector('[aria-label="来源与溯源"]')),
        alert: Boolean(document.querySelector('.admin-workspace [role="alert"]')),
        loading: Boolean(document.querySelector('[aria-label="来源与溯源"] [role="status"]')),
        metadata: Boolean(document.querySelector('[aria-label="来源与溯源"] dl')),
        disabled: document.querySelector('.admin-document button.primary')?.disabled ?? null,
        requests: window.__uiSmokePaths })`)
      throw new Error(`${failure.message}; fixture state ${state}`)
    }
    assert.equal(await page.evaluate('document.querySelector(".admin-document button.primary")?.disabled'), true,
      'A pending usage permission must not offer an active approval action')
    await page.evaluate('window.__uiSmokePermission = "permitted"; true')
    await click('.admin-document .admin-title button.mini')
    await click('.table-card.admin-scroll tbody tr button.mini')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-document button.primary")?.disabled === false'))
    await click('.admin-document button.primary') // intercepted POST; synthetic document only
    await waitUntil(() => page.evaluate('window.__uiSmokeApprovalCalls === 1 && document.querySelector(".admin-document h3")?.innerText === "Synthetic pending approval fixture" && !document.querySelector(".admin-document button.primary")'))
    assert.equal(await page.evaluate('document.querySelector(".admin-document")?.innerText.includes("向量状态")'), true,
      'Approval result must leave the refreshed detail and index status visible')
    await page.evaluate('window.fetch = window.__uiSmokeOriginalFetch; true')
    await click('.admin-document .admin-title button.mini')
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('!document.body.innerText.includes("Synthetic pending approval fixture")'))
    await click('.admin-side a[href*="adminPage=observe"]')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-title h2")?.innerText === "AI 运行观测"'))
    await waitUntil(() => page.evaluate('document.querySelector(".admin-title button.mini")?.innerText === "刷新状态与记录"'))
    await page.evaluate(`(() => {
      const original = window.fetch.bind(window)
      window.__uiSmokeRefresh = { started: 0, pending: 0 }
      window.fetch = (...args) => {
        window.__uiSmokeRefresh.started++
        window.__uiSmokeRefresh.pending++
        return original(...args).finally(() => window.__uiSmokeRefresh.pending--)
      }
      return true
    })()`)
    await click('.admin-title button.mini') // refreshes existing technical records; does not invoke a model
    await waitUntil(() => page.evaluate('window.__uiSmokeRefresh?.started >= 4 && window.__uiSmokeRefresh.pending === 0 && document.querySelector(".admin-title button.mini")?.innerText === "刷新状态与记录"'))
    assert.equal(await page.evaluate('document.querySelector(".admin-workspace [role=alert]")?.innerText || null'), null,
      'Observation refresh did not load all technical endpoints')
    await page.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
      const original = window.fetch.bind(window)
      window.__toolsRequests = []
      window.fetch = (input, init) => { window.__toolsRequests.push(String(input)); return original(input, init) }
    })()` })
    await click('.admin-side a[href*="adminPage=tools"]')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-title h2")?.innerText === "Agent 工具中心" && document.querySelectorAll(".admin-scroll tbody tr").length > 0'))
    assert.deepEqual(await page.evaluate('window.__toolsRequests.filter(path => path.startsWith("/api/admin/"))'), ['/api/admin/tools'],
      'Opening tools should not preload unrelated admin resources')
    await page.evaluate(`(() => {
      const original = window.fetch.bind(window)
      window.__toolFixtureFail = true
      window.__toolFixtureWrites = 0
      const tool = {code:'department_search',name:'Synthetic directory',description:'Browser fixture only',enabled:true}
      const reply = (value, status=200) => Promise.resolve(new Response(JSON.stringify(value), {status,headers:{'Content-Type':'application/json'}}))
      window.fetch = (input, init) => {
        const url = String(input)
        if(url === '/api/admin/tools') return window.__toolFixtureFail ? reply({message:'Synthetic list unavailable'},503) : reply([tool])
        if(url.startsWith('/api/admin/tools/') && init?.method === 'PATCH') {
          window.__toolFixtureWrites++; return reply({message:'Synthetic uncertain response'},503)
        }
        if(url === '/api/admin/tools/department_search/run' && init?.method === 'POST') {
          window.__toolFixtureWrites++
          if(JSON.parse(init.body).department !== 'Synthetic') throw Error('Wrong protocol parameter')
          return reply({data:{exists:false},trace:{success:true,outcome:'Synthetic no department',elapsedMs:7}})
        }
        if(url.startsWith('/api/admin/tools/') && init?.method) throw Error('Unexpected fixture write')
        return original(input,init)
      }
      return true
    })()`)
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-tool-error")?.innerText.includes("Synthetic list unavailable")'))
    assert.equal(await page.evaluate('Array.from(document.querySelectorAll(".admin-scroll tbody button")).every(button => button.disabled)'), true)
    await page.evaluate('window.__toolFixtureFail = false; true')
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('document.querySelectorAll(".admin-scroll tbody tr").length === 1 && !document.querySelector(".admin-tool-error")'))
    await click('.admin-scroll tbody button.mini')
    await page.evaluate(`(() => { const input = document.querySelector('.admin-tool-trial input'); input.value='Synthetic'; input.dispatchEvent(new Event('input',{bubbles:true})); return true })()`)
    await click('.admin-tool-trial button.primary')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-tool-trial pre")?.innerText.includes("false")'))
    assert.equal(await page.evaluate('window.__toolFixtureWrites'),1)
    await click('.admin-scroll tbody button.switch')
    await waitUntil(() => page.evaluate('document.querySelector(".admin-tool-error")?.innerText.includes("状态变更结果待确认")'))
    assert.equal(await page.evaluate('document.querySelector(".admin-scroll tbody button.switch")?.disabled'),true)
    assert.equal(await page.evaluate('window.__toolFixtureWrites'),2, 'No automatic write retry after uncertain toggle')
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('!document.querySelector(".admin-tool-error") && document.querySelector(".admin-scroll tbody button.switch")?.disabled === false'))
    await click('.admin-side a[href*="adminPage=catalog"]')
    await waitUntil(() => page.evaluate('document.querySelector(".catalog-fields")?.disabled === false'))
    assert.deepEqual(await page.evaluate('window.__toolsRequests.filter(path => path.startsWith("/api/admin/"))'),
      ['/api/admin/catalog/departments', '/api/admin/catalog/doctors'], 'Catalog initial load must read each resource only once')
    await page.evaluate(`(() => {
      const original = window.fetch.bind(window)
      window.__catalogFixtureWrites = 0
      window.__catalogFixtureReadFails = true
      const department = {id:'ui-smoke-department',name:'Synthetic department',enabled:true}
      const reply = (data,status=200) => Promise.resolve(new Response(JSON.stringify(data), {status,headers:{'Content-Type':'application/json'}}))
      window.fetch = (input,init) => {
        const url=String(input)
        if(url === '/api/admin/catalog/departments' && init?.method === 'POST') {
          window.__catalogFixtureWrites++;return reply(department)
        }
        if(url.startsWith('/api/admin/catalog/')) {
          if(init?.method && init.method !== 'GET') throw Error('Unexpected catalog fixture write')
          return window.__catalogFixtureReadFails ? reply({message:'Synthetic read failure'},503)
            : reply(url.endsWith('/departments') ? [department] : [])
        }
        return original(input,init)
      }
      const input=document.querySelector('.catalog-form input')
      input.value='Synthetic department';input.dispatchEvent(new Event('input',{bubbles:true}))
      return true
    })()`)
    await click('.catalog-form button.primary')
    await waitUntil(() => page.evaluate('document.querySelector(".catalog-error")?.innerText.includes("操作已成功，但目录刷新失败")'))
    assert.equal(await page.evaluate('document.querySelector(".catalog-fields")?.disabled'),true)
    assert.equal(await page.evaluate('window.__catalogFixtureWrites'),1)
    await page.evaluate('window.__catalogFixtureReadFails = false; true')
    await click('.admin-title button.mini')
    await waitUntil(() => page.evaluate('document.querySelector(".catalog-fields")?.disabled === false && !document.querySelector(".catalog-error")'))
    assert.equal(await page.evaluate('window.__catalogFixtureWrites'),1, 'Refreshing after a successful write must never create again')
    await page.evaluate('localStorage.setItem("ui-smoke-retain", "fixture"); true')
    await click('.account-menu summary')
    await click('.account-logout')
    await waitUntil(() => page.evaluate('Boolean(document.querySelector(".login-form"))'))
    assert.equal(await page.evaluate('localStorage.getItem("ai-hospital-token")'),null)
    assert.equal(await page.evaluate('localStorage.getItem("ai-hospital-login")'),null)
    assert.equal(await page.evaluate('localStorage.getItem("ui-smoke-retain")'),'fixture', 'Logout must not clear unrelated storage')
    assert.deepEqual(page.pageErrors, [], 'Browser reported an uncaught exception')
    console.log(`Read-only UI smoke passed: patient triage → synthetic history read/reset → visits; admin knowledge load/failure/recovery${hasDocuments ? ' → document detail' : ' (empty catalog)'} → synthetic permission guard/approval refresh → observation refresh → tools single-load/failure/recovery/synthetic trial/uncertain toggle → catalog single-load/synthetic write/read-back failure/recovery.`)
  } finally {
    page?.socket.close()
    child.kill()
    await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 1500))])
    await fs.rm(profile, { recursive: true, force: true })
  }
}

run().catch(error => { console.error(`Read-only UI smoke failed: ${error.message}`); process.exitCode = 1 })
