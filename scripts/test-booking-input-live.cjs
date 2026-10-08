// Explicit local integration probe: invalid requests only; no booking or model call.
const assert = require('node:assert/strict')
const base = (process.env.AI_HOSPITAL_BASE_URL || 'http://127.0.0.1:8081').replace(/\/$/, '')
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname), 'Local service only')
async function main() {
  const login = await fetch(base + '/api/auth/login', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({username: 'lisi', password: '123456'}), signal: AbortSignal.timeout(10000)
  })
  assert.equal(login.status, 200)
  const {token} = await login.json()
  const headers = {'Content-Type': 'application/json', Authorization: 'Bearer ' + token}
  async function read(path) {
    const response = await fetch(base + '/api' + path, {headers, signal: AbortSignal.timeout(10000)})
    assert.equal(response.status, 200)
    return response.json()
  }
  const before = await read('/appointments')
  const doctors = await read('/doctors')
  const doctorId = doctors[0]?.id || 'invalid-input-probe'
  for (const body of [{doctorId}, {doctorId, sessionId: ''}, {sessionId: 'walk-in'},
    {doctorId: '', sessionId: 'walk-in'}, {doctorId, sessionId: 'x'.repeat(101)}]) {
    const response = await fetch(base + '/api/appointments', {
      method: 'POST', headers,
      body: JSON.stringify({...body, idempotencyKey: 'invalid-' + crypto.randomUUID()}),
      signal: AbortSignal.timeout(10000)
    })
    assert.equal(response.status, 400, 'Invalid identity must be rejected before booking')
  }
  assert.deepEqual(await read('/appointments'), before, 'Invalid requests changed receipts')
  assert.deepEqual(await read('/doctors'), doctors, 'Invalid requests changed catalogue or slots')
  console.log(JSON.stringify({passed: true, invalidRequests: 5, appointmentDelta: 0,
    availableDoctors: doctors.length, slotDelta: doctors.length ? 0 : null, modelCalls: 0}))
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
