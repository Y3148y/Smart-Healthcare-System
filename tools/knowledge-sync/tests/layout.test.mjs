import test from 'node:test';
import assert from 'node:assert/strict';
import {join} from 'node:path';
import {ROOT,CACHE_DIR,REPORTS_DIR} from '../lib/registry.mjs';

test('source snapshots and generated reports use the business layout, not tool or agent scratch folders',()=>{
  assert.equal(CACHE_DIR,join(ROOT,'knowledge','sources'));
  assert.equal(REPORTS_DIR,join(ROOT,'reports','knowledge-sync'));
});
