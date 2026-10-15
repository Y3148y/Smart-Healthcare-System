const test = require('node:test')
const assert = require('node:assert/strict')
const {readFileSync} = require('node:fs')
const {resolve} = require('node:path')
const {spawnSync}=require('node:child_process')
const read = file => readFileSync(resolve(__dirname,'..',file),'utf8')

test('container contexts are explicit allowlists and runtime stages do not copy local workspaces', () => {
  for (const module of ['backend','frontend']) {
    const ignore = read(`${module}/.dockerignore`)
    assert.equal(ignore.split(/\r?\n/)[0], '*')
    assert.doesNotMatch(ignore, /^!.*(?:\.env|target|node_modules|reports|knowledge|\.codex)/m)
    const dockerfile = read(`${module}/Dockerfile`)
    assert.match(dockerfile,/ AS build/)
    assert.match(dockerfile,/COPY --from=build/)
    assert.doesNotMatch(dockerfile,/(?:ARG|ENV) .*API_KEY|COPY .*\.env/)
  }
  assert.match(read('backend/Dockerfile'),/USER 10001:10001/)
})

test('Nginx preserves streaming stages and separates SPA fallback from API proxying', () => {
  const config=read('frontend/docker/nginx.conf')
  assert.match(config,/location \/api\/\s*\{/)
  assert.match(config,/proxy_pass http:\/\/backend:8081;/)
  assert.match(config,/proxy_buffering off;/)
  assert.match(config,/proxy_read_timeout 240s;/)
  assert.match(config,/try_files \$uri \$uri\/ \/index\.html;/)
  assert.match(config,/client_max_body_size 5m;/)
})

test('container liveness does not call model providers and application restart policy is explicit', () => {
  const probe=read('backend/docker/HealthProbe.java')
  assert.match(probe,/127\.0\.0\.1:/)
  assert.match(probe,/\/api\/health\/live/)
  assert.doesNotMatch(probe,/AI_API_KEY|embeddings|qdrant/)
  const compose=read('compose.app.yml')
  assert.match(compose,/restart: unless-stopped/)
  assert.match(compose,/condition: service_healthy/)
  assert.match(compose,/AI_DEMO_LOCAL_CONTAINER: \$\{AI_DEMO_LOCAL_CONTAINER:-false\}/)
  const published = compose.split(/\r?\n/).filter(line => /^\s+ports:/.test(line))
  assert.equal(published.length,2)
  published.forEach(line => assert.match(line,/127\.0\.0\.1:/))
  assert.doesNotMatch(compose,/privileged: true|network_mode: host/)
})

test('container write probe refuses implicit target or missing apply before making requests', () => {
  const script=resolve(__dirname,'test-container-smoke.cjs')
  const env={...process.env};delete env.CONTAINER_TEST_BASE_URL
  for (const args of [[],['--apply']]) {
    const result=spawnSync(process.execPath,[script,...args],{env,encoding:'utf8'})
    assert.equal(result.status,1)
    assert.match(result.stderr,/Live probe disabled|explicit local test base URL/)
  }
})
