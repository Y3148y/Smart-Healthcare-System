import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {reviewOutputPath} from '../review-output-path.mjs';

test('review output is confined to the ignored local reports/rag directory',()=>{
  const root=mkdtempSync(join(tmpdir(),'rag-review-path-'));
  try {
    mkdirSync(join(root,'reports','rag'),{recursive:true});
    assert.equal(reviewOutputPath(root,'reports/rag/review.json'),resolve(root,'reports','rag','review.json'));
    assert.throws(()=>reviewOutputPath(root,'reports/review.json'),/directly under reports\/rag/);
    assert.throws(()=>reviewOutputPath(root,'C:/Users/Public/review.json'),/directly under reports\/rag/);
  } finally {rmSync(root,{recursive:true,force:true});}
});
