const test = require('node:test')
const assert = require('node:assert/strict')
const {configuration, streamResult} = require('./test-container-journey.cjs')
const {spawnSync} = require('node:child_process')
const {resolve} = require('node:path')

test('journey refuses implicit targets, live configuration and missing apply before I/O', () => {
  const env = {CONTAINER_TEST_BASE_URL: 'http://127.0.0.1:50000', CONTAINER_TEST_PASSWORD:'synthetic', AI_MODE:'demo', AI_AGENT_MODE:'OFF'}
  assert.equal(configuration(env, ['--apply']).base, env.CONTAINER_TEST_BASE_URL)
  for (const [changes,args] of [[{},[]],[{CONTAINER_TEST_BASE_URL:undefined},['--apply']],
    [{AI_MODE:'openai-compatible'},['--apply']], [{AI_AGENT_MODE:'ENABLED'},['--apply']],
    [{CONTAINER_TEST_PASSWORD:''},['--apply']], [{CONTAINER_TEST_BASE_URL:'https://example.com'},['--apply']],
    [{},['--apply','--force']]]) assert.throws(() => configuration({...env,...changes},args))
})

test('stage parser accepts one final result and rejects missing, failed or duplicate final events', () => {
  const status = 'event:status\ndata:{"stage":"ACCEPTED"}\n\n'
  const result = 'event:result\ndata:{"messages":[]}\n\n'
  assert.deepEqual(streamResult((status+result).replaceAll('\n','\r\n')), {messages:[]})
  for (const invalid of [status,result,status+result+result,status+'event:failure\ndata:{}\n\n'+result])
    assert.throws(() => streamResult(invalid))
})

test('live ES deletion probe refuses implicit and non-loopback targets before creating indices', () => {
  for (const [target,args] of [[undefined,['--apply']], ['http://127.0.0.1:1',[]],
    ['http://example.invalid',['--apply']]]) {
    const env = {...process.env}; delete env.ES_SNAPSHOT_TEST_URL
    if (target) env.ES_SNAPSHOT_TEST_URL = target
    const child = spawnSync(process.execPath,[resolve(__dirname,'test-es-snapshot-live.mjs'),...args],
      {env,encoding:'utf8',timeout:5000})
    assert.equal(child.status,1); assert.equal(child.stdout,'')
    assert.match(child.stderr,/verification failed/)
  }
})
