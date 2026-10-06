import test from 'node:test';
import assert from 'node:assert/strict';
import {checkCitations} from '../lib/citations.mjs';
const rules = {rules:[{code:'FIXTURE',citations:[{id:'old-id'}]}]};
test('declared alias resolves to one canonical source without duplicate fetching', () => {
  assert.deepEqual(checkCitations(rules,{sources:[{id:'canonical',aliases:['old-id'],url:'https://example.invalid',status:'verified',fetchMode:'auto'}]}), []);
});
test('aliases do not hide unresolved provenance', () => {
  const results = checkCitations(rules,{sources:[{id:'canonical',aliases:['old-id'],url:'',status:'toVerify'}]});
  assert.equal(results[0].level,'error');
});
test('ambiguous source IDs or aliases fail rather than taking last entry', () => {
  assert.throws(() => checkCitations(rules,{sources:[{id:'canonical',aliases:['old-id']},{id:'old-id'}]}), /Duplicate source ID/);
});
