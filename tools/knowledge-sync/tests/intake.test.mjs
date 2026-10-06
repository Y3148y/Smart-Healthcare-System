import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { buildCandidate } from '../lib/intake.mjs';
const raw = Buffer.from('<main><h1>Title</h1><p>Complete condition and exception.</p></main>');
const source = {id:'test-source',name:'Title',publisher:'Fixture',url:'https://example.invalid',licenseNote:'claimed permitted'};
const record = {status:'new',fetchedAt:'2026-10-06T00:00:00Z',sha256:createHash('sha256').update(raw).digest('hex')};
test('complete local content has provenance but never inherits publication permission', () => {
  const candidate = buildCandidate(source, record, raw);
  assert.match(candidate.body, /Complete condition and exception/);
  assert.equal(candidate.metadata.sources[0].rawSha256, record.sha256);
  assert.equal(candidate.metadata.permissionStatus, 'pending');
  assert.equal(candidate.metadata.permissionEvidence, null);
  assert.equal('permissionProof' in candidate.metadata, false);
  assert.equal(candidate.reviewStatus, 'PENDING');
  assert.deepEqual(candidate.metadata.evidenceUses, []);
});
test('wrong hash, failed fetch and unsafe identifiers rejected', () => {
  assert.throws(() => buildCandidate(source, {...record,sha256:'0'.repeat(64)}, raw), /HASH_MISMATCH/);
  assert.throws(() => buildCandidate(source, {...record,status:'http-error'}, raw), /NO_SUCCESSFUL_FETCH/);
  assert.throws(() => buildCandidate({...source,id:'../bad'},record,raw), /INVALID_SOURCE_ID/);
});
