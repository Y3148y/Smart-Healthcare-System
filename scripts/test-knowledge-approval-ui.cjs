// Mutating nonmedical fixture. Requires Playwright in NODE_PATH and an isolated demo stack.
const base = require('./local-test-target.cjs').localTestTarget(process.env.ADMIN_TEST_BASE_URL)
if (process.env.AI_MODE !== 'demo' || process.env.AI_AGENT_MODE !== 'OFF')
  throw new Error('Confirm isolated demo/OFF configuration before browser writes')
const {chromium} = require('playwright')
const assert = require('node:assert/strict')
const {randomUUID} = require('node:crypto')

;(async () => {
  const browser = await chromium.launch({headless: true,
    executablePath: process.env.ADMIN_TEST_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  const page = await browser.newPage()
  const marker = 'syntheticknowledge' + randomUUID().replaceAll('-', '')
  const body = `${marker}\n\n中文文件读取测试：仅验证文本传输，不含医学建议。\n\nNonmedical browser fixture. Contains no medical advice.`
  let documentId, headers
  try {
    await page.goto(base + '/?demo=admin&page=admin&adminPage=knowledge')
    await page.getByRole('button', {name: '新增资料', exact: true}).click()
    await page.getByLabel('资料标题', {exact: true}).fill(marker)
    const upload = page.getByLabel('读取本地正文或待审包')
    await upload.setInputFiles({name: 'empty.md', mimeType: 'text/markdown', buffer: Buffer.alloc(0)})
    await page.getByRole('alert').filter({hasText: '每个不超过4MB'}).waitFor()
    await upload.setInputFiles({name: marker + '.md', mimeType: 'text/markdown', buffer: Buffer.from(body, 'utf8')})
    await page.waitForFunction(expected => document.querySelector('textarea')?.value === expected, body)
    assert.equal(await page.getByLabel('资料正文', {exact: true}).inputValue(), body)
    await page.getByLabel('来源登记 ID', {exact: true}).fill(marker)
    await page.getByLabel('发布机构', {exact: true}).fill('Synthetic test author')
    await page.getByLabel('来源链接', {exact: true}).fill('https://example.invalid/synthetic-test')
    await page.getByLabel('证据用途').selectOption('general_information')
    await page.getByLabel('使用许可状态').selectOption('permitted')
    await page.getByLabel('使用许可依据', {exact: true}).fill('Self-authored nonmedical test fixture; isolated test use only.')
    await page.getByRole('checkbox', {name: '我已核对本次内容的使用许可'}).check()
    const created = page.waitForResponse(r => r.request().method() === 'POST'
      && new URL(r.url()).pathname === '/api/admin/knowledge/documents')
    await page.getByRole('button', {name: '提交待审批', exact: true}).click()
    const response = await created
    assert.equal(response.status(), 200)
    const document = await response.json()
    documentId = document.id
    assert.equal(document.title, marker)
    assert.equal(document.status, 'PENDING_REVIEW')
    headers = {Authorization: 'Bearer ' + await page.evaluate(() => localStorage.getItem('ai-hospital-token'))}
    async function read(path) {
      const result = await page.request.get(base + '/api' + path, {headers})
      assert.equal(result.status(), 200)
      return result.json()
    }
    const search = () => read('/admin/knowledge/search?q=' + marker)
    assert.equal((await search()).some(r => r.title === marker), false)
    const panel = page.getByRole('region', {name: '文档详情'})
    await panel.getByRole('heading', {name: marker, exact: true}).waitFor()
    await panel.getByText('查看完整原文', {exact: true}).click()
    assert.equal(await panel.locator('pre').innerText(), body)
    assert.ok((await read(`/admin/knowledge/${documentId}/details`)).segments.length)
    await page.waitForFunction(() => [...document.querySelectorAll('button')]
      .some(b => b.textContent === '核对后审批通过' && !b.disabled))
    await panel.getByRole('button', {name: '核对后审批通过', exact: true}).click()
    await page.getByRole('status').filter({hasText: '审批完成'}).waitFor()
    assert.equal((await search()).some(r => r.title === marker), true)
    assert.equal((await read(`/admin/knowledge/${documentId}/metadata`)).clinicalReview, 'NOT_REVIEWED')
    page.once('dialog', dialog => dialog.accept())
    await page.getByRole('button', {name: '撤回到待审批', exact: true}).click()
    await page.getByRole('status').filter({hasText: '资料已撤回到待审批'}).waitFor()
    assert.equal((await search()).some(r => r.title === marker), false)
    console.log(JSON.stringify({passed: true, mode: 'SYNTHETIC_DEMO_ONLY',
      checks: ['browser-invalid-file-rejected', 'browser-markdown-file-read', 'browser-source-metadata-entry', 'pending-excluded', 'full-body-and-chunks',
        'browser-approval', 'real-lexical-retrieval', 'clinical-review-not-fabricated', 'browser-withdrawal']}))
  } finally {
    // Withdraw only the exact self-created fixture if a later assertion interrupted the journey.
    try {
      if (documentId && headers) {
        const result = await page.request.get(base + `/api/admin/knowledge/${documentId}/details`, {headers})
        assert.equal(result.status(), 200)
        const detail = await result.json()
        assert.equal(detail.document.title, marker)
        if (detail.document.status === 'READY') {
          const withdrawn = await page.request.post(base + `/api/admin/knowledge/${documentId}/withdraw`, {headers})
          assert.equal(withdrawn.status(), 200)
        }
      }
    } finally {await browser.close()}
  }
})().catch(error => {console.error(error); process.exitCode = 1})
