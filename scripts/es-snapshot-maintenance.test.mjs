import test from 'node:test';
import assert from 'node:assert/strict';
import { options, plan, maintain } from './es-snapshot-maintenance.mjs';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';

const current = 'ai-hospital-lexical-' + 'a'.repeat(64);
const old = 'ai-hospital-lexical-' + 'b'.repeat(64);
const now = 1800000000000;
const describe = (name, created = now - 172800000, uuid = 'fixture-id') => ({ aliases: {},
  mappings: { _meta: { schema: 'cjk-bm25-v1', snapshot: name.slice(-64) } },
  settings: { index: { creation_date: String(created), uuid } } });
const args = ['--url', 'http://127.0.0.1:9201', '--keep', current];
const inventory = () => ({ [current]: describe(current), [old]: describe(old) });

test('default inventory is read-only and protects explicit current snapshot', async () => {
  const calls = [];
  const result = await maintain(options(args), async (method, path) => { calls.push([method, path]); return inventory(); }, now);
  assert.equal(result.mode, 'DRY_RUN'); assert.deepEqual(result.deleted, []);
  assert.equal(calls.length, 1); assert.equal(calls[0][0], 'GET');
  assert.equal(result.indices.find(row => row.name === current).eligible, false);
  assert.equal(result.indices.find(row => row.name === old).eligible, true);
});

test('reject external origins, wildcard deletes, absent protection and unsupported flags before I/O', () => {
  for (const bad of [[], ['--url', 'http://example.com', '--keep', current],
    [...args, '--delete', 'ai-hospital-lexical-*'], [...args, '--apply'],
    [...args, '--apply', '--delete', old], [...args, '--delete', current],
    [...args, '--min-age-hours', '1'], [...args, '--force'],
    ['--url', 'http://user@127.0.0.1:9201', '--keep', current]]) assert.throws(() => options(bad));
});

test('reject foreign ownership, aliases, missing keep and invalid timestamps', () => {
  for (const change of [value => { value[old].mappings._meta.schema = 'foreign'; },
    value => { value[old].aliases.live = {}; }, value => { delete value[current]; },
    value => { value[old].settings.index.creation_date = 'invalid'; },
    value => { value[old].mappings._meta.snapshot = 'c'.repeat(64); },
    value => { value['unrelated-index'] = describe(old); }]) {
    const value = inventory(); change(value); assert.throws(() => plan(options(args), value, now));
  }
});

test('recent, future and explicitly absent deletion targets produce no DELETE', async () => {
  for (const value of [{ [current]: describe(current), [old]: describe(old, now - 1000) },
    { [current]: describe(current), [old]: describe(old, now + 1000) }, { [current]: describe(current) }]) {
    const methods = [];
    await assert.rejects(maintain(options([...args, '--delete', old, '--apply', '--apps-stopped']),
      async method => { methods.push(method); return value; }, now));
    assert.deepEqual(methods, ['GET']);
  }
});

test('explicit apply checks protected and removed UUIDs and deletes only exact selected index', async () => {
  const calls = [];
  const result = await maintain(options([...args, '--delete', old, '--apply', '--apps-stopped']), async (method, path) => {
    calls.push([method, path]);
    if (method === 'DELETE') return { acknowledged: true };
    if (path.includes('*')) return inventory();
    const name = path.slice(1).split('?')[0]; return { [name]: describe(name) };
  }, now);
  assert.deepEqual(result.deleted, [old]);
  assert.equal(calls.filter(([method]) => method === 'GET').length, 3);
  assert.deepEqual(calls.filter(([method]) => method === 'DELETE'), [['DELETE', `/${old}?expand_wildcards=none`]]);
});

test('identity drift or preflight failure stops before any deletion', async () => {
  const calls = [];
  await assert.rejects(maintain(options([...args, '--delete', old, '--apply', '--apps-stopped']), async (method, path) => {
    calls.push(method); if (path.includes('*')) return inventory();
    const name = path.slice(1).split('?')[0]; return { [name]: describe(name, now - 172800000, 'changed-id') };
  }, now));
  assert.equal(calls.includes('DELETE'), false);
});

test('uncertain deletion is not retried or reported as success', async () => {
  let deletions = 0;
  await assert.rejects(maintain(options([...args, '--delete', old, '--apply', '--apps-stopped']), async (method, path) => {
    if (method === 'DELETE') { deletions++; return { acknowledged: false }; }
    if (path.includes('*')) return inventory();
    const name = path.slice(1).split('?')[0]; return { [name]: describe(name) };
  }, now));
  assert.equal(deletions, 1);
});

test('CLI performs real loopback HTTP inventory without write calls or credential output', async () => {
  const calls = [];
  const server = createServer((request, response) => {
    calls.push(request.method);
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(inventory()));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const child = spawn(process.execPath, ['scripts/es-snapshot-maintenance.mjs', '--url',
      `http://127.0.0.1:${server.address().port}`, '--keep', current],
      { env: { ...process.env, AI_ELASTICSEARCH_API_KEY: 'synthetic-api-key' }, timeout: 15000 });
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
    const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
    assert.equal(code, 0, stderr); assert.deepEqual(calls, ['GET']);
    assert.equal(JSON.parse(stdout).mode, 'DRY_RUN');
    assert.equal((stdout + stderr).includes('synthetic-api-key'), false);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
