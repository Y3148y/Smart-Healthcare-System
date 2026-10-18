// Mutating synthetic-data probe. Run only against an explicitly isolated demo stack.
const assert = require('node:assert/strict')
const {randomUUID} = require('node:crypto')
const {localTestTarget} = require('./local-test-target.cjs')

function configuration(env, args) {
  const base = localTestTarget(env.CONTAINER_TEST_BASE_URL, args)
  if (args.length !== 1 || args[0] !== '--apply') throw new Error('Only explicit --apply is supported')
  if (env.AI_MODE !== 'demo' || env.AI_AGENT_MODE !== 'OFF')
    throw new Error('Confirm isolated demo/OFF configuration in the launching environment')
  if (!env.CONTAINER_TEST_PASSWORD) throw new Error('CONTAINER_TEST_PASSWORD is required')
  return {base, password: env.CONTAINER_TEST_PASSWORD}
}

function streamResult(text) {
  const frames = text.replaceAll('\r\n', '\n').split('\n\n').filter(value => value.trim())
    .map(frame => ({event: frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim(),
      data: frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')}))
  assert.equal(frames.some(frame => frame.event === 'failure'), false, 'Stage stream failed')
  assert.ok(frames.some(frame => frame.event === 'status'), 'No stage notification')
  const results = frames.filter(frame => frame.event === 'result')
  assert.equal(results.length, 1, 'Expected one persisted final result')
  return JSON.parse(results[0].data)
}

async function journey(config, onStage = () => {}) {
  onStage('AUTH_AND_DEPENDENCIES')
  const tokens = {}
  const request = async (role, method, path, body, expected = 200, streaming = false) => {
    const response = await fetch(config.base + '/api' + path, {method, redirect: 'error',
      headers: {'Content-Type': 'application/json', Origin: config.base,
        ...(tokens[role] ? {Authorization: `Bearer ${tokens[role]}`} : {})},
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(45000)})
    assert.equal(response.status, expected, `${method} ${path}: unexpected HTTP status`)
    if (streaming) {
      assert.match(response.headers.get('content-type') || '', /text\/event-stream/)
      return streamResult(await response.text())
    }
    return response.json()
  }
  for (const [role, username] of [['admin','admin'], ['patient','zhangsan'], ['other','lisi']]) {
    tokens[role] = (await request(null, 'POST', '/auth/login', {username, password: config.password})).token
    assert.ok(tokens[role], 'Missing authentication token')
  }
  assert.equal((await request(null, 'GET', '/health/ready')).status, 'UP')
  const runtime = await request('admin', 'GET', '/admin/knowledge/runtime')
  assert.equal(runtime.embeddingConfigured, 'false', 'Synthetic journey must not call embedding provider')
  assert.equal(runtime.rerankEnabled, 'false', 'Synthetic journey must not call rerank provider')
  const stamp = 'syntheticcontainer' + randomUUID().replaceAll('-', '')

  onStage('PATIENT_PROFILE')
  const original = await request('patient', 'GET', '/patient/profile')
  const profile = {displayName: stamp, birthDate: null, allergies: '', medications: '',
    healthBackground: 'Synthetic test only', selfReportConfirmed: true, version: original.version}
  const saved = await request('patient', 'PUT', '/patient/profile', profile)
  assert.equal(saved.source, 'PATIENT_SELF_REPORT')
  assert.equal(saved.version, original.version + 1)
  await request('patient', 'PUT', '/patient/profile', profile, 409)
  assert.notEqual((await request('other', 'GET', '/patient/profile')).displayName, stamp)

  onStage('KNOWLEDGE_APPROVAL_WITHDRAWAL')
  const doc = await request('admin', 'POST', '/admin/knowledge', {title: stamp, body: stamp + ' synthetic non-medical content.'})
  assert.equal(doc.status, 'PENDING_REVIEW')
  const search = () => request('admin', 'GET', '/admin/knowledge/search?q=' + stamp)
  assert.equal((await search()).some(row => row.title === stamp), false)
  await request('patient', 'POST', `/admin/knowledge/${doc.id}/approve`, undefined, 403)
  assert.equal((await request('admin', 'POST', `/admin/knowledge/${doc.id}/approve`)).status, 'READY')
  assert.equal((await search()).some(row => row.title === stamp), true)
  assert.ok((await request('admin', 'GET', `/admin/knowledge/${doc.id}/details`)).segments.length)
  assert.equal((await request('admin', 'GET', `/admin/knowledge/${doc.id}/metadata`)).clinicalReview, 'NOT_REVIEWED')
  assert.equal((await request('admin', 'POST', `/admin/knowledge/${doc.id}/withdraw`)).status, 'PENDING_REVIEW')
  assert.equal((await search()).some(row => row.title === stamp), false)

  onStage('CATALOG_AND_BOOKING')
  onStage('CATALOG_AND_BOOKING:CREATE_DEPARTMENT')
  const department = await request('admin', 'POST', '/admin/catalog/departments', {name: stamp, enabled: true})
  const date = new Date(); date.setUTCDate(date.getUTCDate() + 2)
  const doctorBody = {name: stamp, title: 'Synthetic', departmentId: department.id, enabled: true,
    date: date.toISOString().slice(0,10), period: '上午', total: 3, fee: 0}
  onStage('CATALOG_AND_BOOKING:CREATE_DOCTOR')
  const doctor = await request('admin', 'POST', '/admin/catalog/doctors', doctorBody)
  const booking = {doctorId: doctor.id, sessionId: 'walk-in', idempotencyKey: randomUUID()}
  onStage('CATALOG_AND_BOOKING:BOOK')
  const receipt = await request('patient', 'POST', '/appointments', booking)
  onStage('CATALOG_AND_BOOKING:IDEMPOTENCY_REPLAY')
  assert.deepEqual(await request('patient', 'POST', '/appointments', booking), receipt)
  onStage('CATALOG_AND_BOOKING:VERIFY_ISOLATION_AND_CAPACITY')
  assert.equal((await request('admin', 'GET', '/admin/catalog/doctors')).find(row => row.id === doctor.id).remaining, 2)
  assert.equal((await request('other', 'GET', '/appointments')).some(row => row.id === receipt.id), false)
  await request('patient', 'GET', '/admin/catalog/doctors', undefined, 403)
  await request('admin', 'PUT', '/admin/catalog/doctors/' + doctor.id, {...doctorBody, total: 0}, 409)

  onStage('STAGE_STREAM_AND_SAFETY_GATE')
  const eligibility = {adultConfirmed: true, forSelfConfirmed: true, notPregnantConfirmed: true}
  onStage('STAGE_STREAM_AND_SAFETY_GATE:CREATE_SESSION')
  const created = await request('patient', 'POST', '/triage/sessions', eligibility)
  const session = '/triage/sessions/' + created.session.id
  const turnId = randomUUID(), question = {content: '胸痛', requestId: turnId}
  onStage('STAGE_STREAM_AND_SAFETY_GATE:STREAM_TURN')
  const answered = await request('patient', 'POST', session + '/turns/stream', question, 200, true)
  onStage('STAGE_STREAM_AND_SAFETY_GATE:VERIFY_EMERGENCY_AND_REPLAY')
  assert.equal(answered.assessments.at(-1).result.safetyAssessment.acuity, 'EMERGENCY')
  assert.equal(answered.assessments.at(-1).result.safetyAssessment.stopRoutineFlow, true)
  assert.deepEqual((await request('patient', 'GET', session)).messages, answered.messages)
  assert.deepEqual((await request('patient', 'POST', session + '/turns', question)).messages, answered.messages)
  assert.equal((await request('patient', 'GET', session + '/turns/' + turnId)).status, 'COMPLETED')
  await request('patient', 'POST', session + '/turns', {...question, content: 'Different question'}, 409)
  await request('other', 'GET', session, undefined, 404)
  onStage('STAGE_STREAM_AND_SAFETY_GATE:BLOCK_BOOKING')
  await request('patient', 'POST', '/appointments', {...booking, sessionId: created.session.id, idempotencyKey: randomUUID()}, 409)
  assert.equal((await request('admin', 'GET', '/admin/catalog/doctors')).find(row => row.id === doctor.id).remaining, 2)
  await request('admin', 'PUT', '/admin/catalog/doctors/' + doctor.id, {...doctorBody, enabled: false})
  assert.equal((await request(null, 'GET', '/doctors')).some(row => row.id === doctor.id), false)
  assert.equal((await request('patient', 'GET', '/appointments')).some(row => row.id === receipt.id), true)
  await request('admin', 'PUT', '/admin/catalog/departments/' + department.id, {name: stamp, enabled: false})

  onStage('HUMAN_REVIEW')
  const review = await request('patient', 'POST', session + '/human-review', {reason: 'Synthetic container review request'})
  assert.equal(review.status, 'PENDING')
  const queued = (await request('admin', 'GET', '/admin/human-reviews')).find(row => row.id === review.id)
  assert.ok(queued, 'Review missing from admin queue')
  assert.equal(queued.summaryAccessible, false, 'This probe requires default-denied summary access')
  await request('admin', 'GET', `/admin/human-reviews/${review.id}/summary`, undefined, 403)
  await request('patient', 'PATCH', `/admin/human-reviews/${review.id}`, {status:'ACCEPTED'}, 403)
  assert.equal((await request('admin', 'PATCH', `/admin/human-reviews/${review.id}`, {status:'ACCEPTED'})).status, 'ACCEPTED')
  assert.equal((await request('admin', 'PATCH', `/admin/human-reviews/${review.id}`, {status:'CLOSED'})).status, 'CLOSED')
  await request('admin', 'PATCH', `/admin/human-reviews/${review.id}`, {status:'ACCEPTED'}, 409)
  assert.equal((await request('patient', 'GET', '/patient/human-reviews')).find(row => row.id === review.id).status, 'CLOSED')
  assert.equal((await request('other', 'GET', '/patient/human-reviews')).some(row => row.id === review.id), false)

  return {profileVersion: true, knowledgeApprovalWithdrawal: true, bookingReplaySingleDecrement: true,
    catalogDisable: true, stageSsePersistenceReplay: true, patientIsolation: true, humanReviewClosed: true,
    summaryDefaultDenied: true, mode: 'SYNTHETIC_DEMO_ONLY'}
}

if (require.main === module) {
  let stage = 'CONFIGURATION'
  Promise.resolve().then(() => configuration(process.env, process.argv.slice(2)))
    .then(config => journey(config, value => { stage = value }))
    .then(result => console.log(JSON.stringify(result)))
    .catch(() => { console.error(`Synthetic container journey failed at ${stage}; do not retry writes against a shared database. Inspect the isolated test instance.`); process.exitCode = 1 })
}
module.exports = {configuration, streamResult, journey}
