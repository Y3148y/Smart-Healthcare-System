// Explicit live integration check. Creates one synthetic lisi session, no booking or catalogue writes.
// Medical text is sent by the configured backend to its existing provider. No credentials or text printed.
const assert = require('node:assert/strict')
const base = (process.env.AI_HOSPITAL_BASE_URL || 'http://127.0.0.1:8081').replace(/\/$/, '')
const endpoint = new URL(base)
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname), 'This probe is local-only')

async function request(path, token, body) {
  const response = await fetch(base + '/api' + path, {
    method: body ? 'POST' : 'GET',
    headers: {'Content-Type': 'application/json', ...(token ? {Authorization: 'Bearer ' + token} : {})},
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(90000)
  })
  return {status: response.status, data: await response.json()}
}
async function login(username) {
  const response = await request('/auth/login', null, {username, password: '123456'})
  assert.equal(response.status, 200, 'Demo test login unavailable')
  return response.data.token
}
async function main() {
  const admin = await login('admin')
  const patient = await login('lisi')
  assert.equal((await request('/admin/calls')).status, 401)
  assert.equal((await request('/admin/calls', patient)).status, 403)
  const runtime = await request('/admin/ai-runtime', admin)
  assert.equal(runtime.status, 200)
  assert.equal(runtime.data.configured, true, 'Live model must be configured')
  const queue = await request('/admin/human-reviews', admin)
  assert.equal(queue.status, 200)
  for (const item of queue.data) {
    for (const field of ['patient', 'reason', 'sessionId']) assert.ok(!(field in item), 'Queue leaks ' + field)
    assert.equal(typeof item.summaryAccessible, 'boolean', 'Old queue implementation still running')
  }
  // Empty grants must deny even a nonexistent ID; never expose existence or summary text.
  if (queue.data.every(item => !item.summaryAccessible)) {
    assert.equal((await request('/admin/human-reviews/00000000-0000-0000-0000-000000000000/summary', admin)).status, 403)
  }
  const created = await request('/triage/sessions', patient, {
    adultConfirmed: true, forSelfConfirmed: true, notPregnantConfirmed: true
  })
  assert.equal(created.status, 200)
  const turn = await request(`/triage/sessions/${created.data.session.id}/turns`, patient, {
    content: '流鼻涕两天，鼻塞，没有发热，想了解一般注意事项，暂时不预约'
  })
  assert.equal(turn.status, 200)
  const message = [...turn.data.messages].reverse().find(item => item.role === 'ASSISTANT')
  assert.ok(message)
  const provenance = message.provenance
  const diagnostics = provenance?.answerEvidence
  assert.ok(diagnostics?.traceId, 'Answer trace missing')
  assert.equal(provenance.turnTrace?.traceId, diagnostics.traceId, 'Per-turn trace missing; reload backend')
  const userMessage = [...turn.data.messages].reverse().find(item => item.role === 'USER')
  assert.equal(provenance.turnTrace.userMessageId, userMessage.id)
  const scoped = await request('/admin/calls?traceId=' + diagnostics.traceId, admin)
  assert.equal(scoped.status, 200)
  assert.ok(scoped.data.length > 0)
  assert.ok(scoped.data.every(item => item.traceId === diagnostics.traceId))
  assert.ok(scoped.data.some(item => item.purpose === 'medical_knowledge_retrieve'))
  assert.ok(scoped.data.some(item => item.purpose === '会话处理'))
  const calls = await request('/admin/calls', admin)
  assert.equal(calls.status, 200)
  for (const call of calls.data) {
    assert.ok(!('user' in call), 'Call leaks actor')
    for (const tool of call.tools || []) {
      for (const field of ['input', 'outcome', 'label', 'error']) assert.ok(!(field in tool), 'Tool leaks ' + field)
    }
  }
  const generation = calls.data.find(item => item.id === diagnostics.traceId)
  assert.ok(generation, 'Answer and call record not linked; restart the new backend')
  for (const field of ['inputTokens', 'outputTokens']) {
    const measured = diagnostics.generation?.[field]
    assert.equal(generation[field], measured > 0 ? measured : null, 'Generation usage mismatch')
  }
  if (diagnostics.supportReview) {
    const review = calls.data.find(item => item.id === diagnostics.traceId + '-review')
    assert.ok(review, 'Separate review audit missing')
    assert.equal(review.success, diagnostics.supportReview.status === 'PASSED')
    for (const field of ['inputTokens', 'outputTokens']) {
      const measured = diagnostics.supportReview[field]
      assert.equal(review[field], measured > 0 ? measured : null, 'Review usage mismatch')
    }
  }
  const live = ['LIVE', 'LIVE_UNGROUNDED'].includes(provenance.modelStatus)
  if (!live || diagnostics.failure) assert.equal(generation.success, false, 'Failed answer counted as successful')
  console.log(JSON.stringify({traceId: diagnostics.traceId, modelStatus: provenance.modelStatus,
    failureCode: diagnostics.failure?.code || null, supportReview: diagnostics.supportReview?.status || null,
    auditLinked: true, queueDeidentified: true, measuredUsageMatched: true}))
  assert.ok(live && !diagnostics.failure, 'Real model answer did not pass; telemetry checks do not imply answer quality')
  assert.equal(diagnostics.supportReview?.status, 'PASSED', 'Support review did not pass')
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
