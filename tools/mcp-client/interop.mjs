import assert from 'node:assert/strict'
import {Client} from '@modelcontextprotocol/sdk/client/index.js'
import {StreamableHTTPClientTransport} from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Read-only integration client. Credentials remain in the invoking process environment.
const endpoint = new URL(process.env.MCP_TEST_BASE_URL || 'invalid:')
assert.ok(endpoint.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname)
  && endpoint.pathname === '/mcp' && !endpoint.username && !endpoint.password && !endpoint.search && !endpoint.hash,
  'Set an explicit loopback /mcp endpoint')
assert.ok(process.env.MCP_TEST_TOKEN && process.env.MCP_TEST_PATIENT_TOKEN, 'Test tokens are required')
const authorization = 'Bearer ' + process.env.MCP_TEST_TOKEN
const boundedFetch = (input, init = {}) => {
  const target = new URL(typeof input === 'string' || input instanceof URL ? input : input.url)
  assert.equal(target.origin, endpoint.origin, 'MCP test must not send credentials to another origin')
  return fetch(input, {...init, redirect: 'error', signal: init.signal
    ? AbortSignal.any([init.signal, AbortSignal.timeout(5000)]) : AbortSignal.timeout(5000)})
}
const transport = new StreamableHTTPClientTransport(endpoint, {
  requestInit: {headers: {Authorization: authorization}}, fetch: boundedFetch,
  reconnectionOptions: {maxRetries: 0, maxReconnectionDelay: 0, initialReconnectionDelay: 0, reconnectionDelayGrowFactor: 1}
})
const client = new Client({name: 'healthcare-official-sdk-interop', version: '1.0.0'})
const timer = setTimeout(() => {console.error('MCP interop deadline exceeded'); process.exit(1)}, 20000)
try {
  await client.connect(transport)
  assert.equal(client.getServerVersion()?.name, 'healthcare-readonly-tools')
  assert.ok(client.getServerCapabilities()?.tools)
  await client.ping()
  const listed = await client.listTools()
  assert.ok(listed.tools.some(tool => tool.name === 'department_search'))
  assert.ok(listed.tools.every(tool => tool.inputSchema.additionalProperties === false && tool.annotations?.readOnlyHint))
  const result = await client.callTool({name: 'department_search', arguments: {department: 'Synthetic MCP unknown department'}})
  assert.equal(result.isError, false)
  const data = JSON.parse(result.content[0].text)
  assert.equal(data.exists, false)
  for (const arguments_ of [{department: 12}, {department: ''}, {department: '   '},
    {department: 'x'.repeat(2201)}, {department: 'test', patient: 'forged'}, {}]) {
    await assert.rejects(() => client.callTool({name: 'department_search', arguments: arguments_}), error => error.code === -32602)
  }
  await assert.rejects(() => client.callTool({name: 'create_appointment', arguments: {}}), error => error.code === -32602)
  const raw = async (headers, body = {jsonrpc: '2.0', id: 9, method: 'ping'}) => boundedFetch(endpoint, {
    method: 'POST', headers: {'Content-Type': 'application/json', 'MCP-Protocol-Version': '2025-11-25', ...headers},
    body: JSON.stringify(body)
  })
  assert.equal((await raw({Authorization: 'Bearer ' + process.env.MCP_TEST_PATIENT_TOKEN})).status, 403)
  assert.equal((await raw({})).status, 401)
  assert.equal((await raw({Authorization: authorization, Origin: 'https://untrusted.invalid'})).status, 403)
  assert.equal((await raw({Authorization: authorization, 'MCP-Protocol-Version': 'invalid'})).status, 400)
  assert.equal((await raw({Authorization: authorization, 'MCP-Protocol-Version': 'invalid'}, {
    jsonrpc: '2.0', id: 10, method: 'initialize', params: {protocolVersion: '2025-11-25',
      capabilities: {}, clientInfo: {name: 'synthetic-invalid-header', version: '1'}}})).status, 400)
  assert.equal((await raw({Authorization: authorization}, {jsonrpc: 2.0, id: 11, method: 'ping'})).status, 400)
  assert.equal((await boundedFetch(endpoint, {headers: {Authorization: authorization}})).status, 405)
  assert.equal((await boundedFetch(endpoint, {method: 'DELETE', headers: {Authorization: authorization}})).status, 405)
  const invalid = await raw({Authorization: authorization}, {jsonrpc: '2.0', id: {forged: true}, method: 'ping'})
  assert.equal(invalid.status, 400)
  assert.equal((await invalid.json()).id, null)
  console.log(JSON.stringify({passed: true, client: '@modelcontextprotocol/sdk@1.32.1', protocol: '2025-11-25',
    checks: ['initialize', 'initialized-notification', 'ping', 'tools-list', 'tools-call', 'strict-arguments',
      'no-write-tool', 'admin-only', 'origin-rejection', 'version-rejection', 'get-405', 'invalid-id']}))
} catch {
  console.error('Official MCP client interoperability failed; inspect the isolated test server without exposing tokens.')
  process.exitCode = 1
} finally {
  clearTimeout(timer)
  await client.close()
}
