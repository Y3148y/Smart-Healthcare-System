import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { options, maintain } from './es-snapshot-maintenance.mjs';
const { localTestTarget } = createRequire(import.meta.url)('./local-test-target.cjs');

async function main() {
  const base = localTestTarget(process.env.ES_SNAPSHOT_TEST_URL);
  const prefix = 'testlexical-' + randomUUID().replaceAll('-', '');
  const hash = text => createHash('sha256').update(text).digest('hex');
  const keep = prefix + '-' + hash('synthetic-keep');
  const obsolete = prefix + '-' + hash('synthetic-remove');
  const created = new Map();
  const request = async (method, path, body, expected = 200) => {
    const response = await fetch(base + path, {method, redirect: 'error', signal: AbortSignal.timeout(10000),
      headers: {'Content-Type':'application/json'}, body:body === undefined ? undefined : JSON.stringify(body)});
    assert.equal(response.status, expected, 'Unexpected synthetic Elasticsearch HTTP status');
    return response.json();
  };
  try {
    for (const name of [keep, obsolete]) {
      await request('PUT', '/' + name, {settings:{number_of_shards:1,number_of_replicas:0},
        mappings:{_meta:{schema:'cjk-bm25-v1',snapshot:name.slice(-64)}}});
      const description = await request('GET', '/' + name);
      created.set(name, description[name].settings.index.uuid);
    }
    const config = options(['--url',base,'--prefix',prefix,'--keep',keep]);
    const dryRun = await maintain(config, request);
    assert.equal(dryRun.indices.length, 2);
    assert.equal(dryRun.indices.some(index => index.eligible), false);
    const apply = options(['--url',base,'--prefix',prefix,'--keep',keep,'--delete',obsolete,'--apps-stopped','--apply']);
    await assert.rejects(maintain(apply, request), /too recent/);
    // Controlled test clock, never exposed as a production CLI option. Actual ES metadata and DELETE are real.
    const clock = Math.max(...dryRun.indices.map(index => index.created)) + 48 * 3600000;
    const result = await maintain(apply, request, clock);
    assert.deepEqual(result.deleted, [obsolete]);
    const remaining = await request('GET', '/' + keep);
    assert.equal(remaining[keep].settings.index.uuid, created.get(keep));
    await request('GET', '/' + obsolete, undefined, 404);
    console.log(JSON.stringify({syntheticOnly:true,realElasticsearchDelete:true,protectedIndexPreserved:true,
      recentIndexRefused:true,retentionAgeTest:'CONTROLLED_CLOCK_48H',automaticProductionCleanup:false}));
  } finally {
    for (const [name, uuid] of created) {
      const response = await fetch(base + '/' + name, {redirect:'error',signal:AbortSignal.timeout(10000)});
      if (response.status === 404) { await response.body?.cancel(); continue; }
      assert.equal(response.status, 200, 'Cleanup inventory unavailable');
      const description = await response.json();
      assert.equal(description[name]?.settings?.index?.uuid, uuid, 'Cleanup identity changed');
      assert.equal(description[name]?.mappings?._meta?.snapshot, name.slice(-64), 'Cleanup ownership changed');
      assert.equal(Object.keys(description[name]?.aliases ?? {}).length, 0, 'Synthetic index acquired alias');
      assert.equal((await request('DELETE', '/' + name + '?expand_wildcards=none')).acknowledged, true);
    }
  }
}
main().catch(() => { console.error('Synthetic Elasticsearch maintenance verification failed. Inspect only the unique testlexical indices created by this run; no automatic retry.'); process.exitCode = 1; });
