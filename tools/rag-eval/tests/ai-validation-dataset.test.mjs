import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateCases} from '../lib.mjs';

test('AI validation queries are explicit, separate from author development and not presented as human gold',()=>{
  const dataset=validateCases(JSON.parse(readFileSync(new URL('../cases-ai-validation.json',import.meta.url),'utf8')));
  const development=JSON.parse(readFileSync(new URL('../cases-public-corpus-development.json',import.meta.url),'utf8'));
  assert.equal(dataset.split,'AI_DESIGNED_VALIDATION');assert.equal(dataset.labelAuthor,'AI_REVIEWER');
  assert.equal(dataset.reviewStatus,'NOT_CLINICALLY_REVIEWED');assert.match(dataset.note,/Not human gold/);
  assert.equal(dataset.cases.length,12);assert.equal(dataset.cases.filter(item=>item.expectedTitle===null).length,1);
  const prior=new Set(development.cases.map(item=>item.query));
  assert.ok(dataset.cases.every(item=>!prior.has(item.query)));
  assert.equal(dataset.cases.find(item=>item.id==='ai-v11').query,'嘴唇和喉咙突然肿、呼吸费力，但没有皮疹，能排除严重过敏吗？');
});
