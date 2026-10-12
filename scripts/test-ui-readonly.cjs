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
  throw new Error('Timed out waiting for the expected UI state')
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
    async function open(route, marker) {
      await page.send('Page.navigate', { url: new URL(route, base).href })
      await waitUntil(() => page.evaluate(`document.body && document.body.innerText.includes(${JSON.stringify(marker)})`))
    }
    async function click(selector) {
      assert.ok(await page.evaluate(`Boolean(document.querySelector(${JSON.stringify(selector)}))`), `Missing ${selector}`)
      await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click(); true`)
    }
    await open('/?demo=patient&page=triage', 'AI 门诊预问诊')
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
    const hasDocuments = await page.evaluate('Boolean(document.querySelector(".table-card.admin-scroll tbody tr button.mini"))')
    if (hasDocuments) {
      await click('.table-card.admin-scroll tbody tr button.mini') // GET detail only; never approve
      await waitUntil(() => page.evaluate('Boolean(document.querySelector("[aria-label=\"文档详情\"]"))'))
      await click('[aria-label="文档详情"] summary')
      assert.ok(await page.evaluate('document.querySelector("[aria-label=\"文档详情\"] pre")?.innerText.length > 0'))
      assert.equal(await page.evaluate('document.querySelector(".admin-workspace [role=alert]")?.innerText || null'), null,
        'Document details or provenance failed to load')
    }
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
    assert.deepEqual(page.pageErrors, [], 'Browser reported an uncaught exception')
    console.log(`Read-only UI smoke passed: patient triage → visits; admin dashboard → knowledge intake toggle${hasDocuments ? ' → document detail' : ' (empty catalog)'} → observation refresh.`)
  } finally {
    page?.socket.close()
    child.kill()
    await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 1500))])
    await fs.rm(profile, { recursive: true, force: true })
  }
}

run().catch(error => { console.error(`Read-only UI smoke failed: ${error.message}`); process.exitCode = 1 })
