import test from 'node:test';
import assert from 'node:assert/strict';
import {validateCases,evaluateCase,summarize} from '../lib.mjs';
const report = {semanticStatus:'READY',rerankStatus:'OK',retrieval:{evidence:[{title:'Fixture'}]},candidates:[]};
test('false selection fails even when dependencies succeed', () => {
  const result = evaluateCase({id:'absent',query:'fixture',expectedTitle:null},report);
  assert.equal(result.passed,false);
  assert.equal(summarize([result]).noCoverageFalseSelections,1);
});
test('dependency failure cannot pass by returning empty evidence', () => {
  assert.equal(evaluateCase({id:'absent',query:'fixture',expectedTitle:null},
    {...report,semanticStatus:'SEARCH_UNAVAILABLE',retrieval:{evidence:[]}}).passed,false);
});
test('covered topic selection passes but extras remain flagged for review', () => {
  const result = evaluateCase({id:'covered',query:'fixture',expectedTitle:'Fixture'},
    {...report,retrieval:{evidence:[{title:'Fixture'},{title:'Other'}]}});
  assert.equal(result.passed,true);
  assert.deepEqual(result.extraSelectionsForReview,['Other']);
});
test('missing negative label and duplicate IDs fail dataset validation', () => {
  const dataset={schemaVersion:1,labelVersion:'fixture',reviewStatus:'NOT_CLINICALLY_REVIEWED',cases:[{id:'one',query:'fixture'}]};
  assert.throws(()=>validateCases(dataset));
  dataset.cases=[{id:'one',query:'fixture',expectedTitle:null},{id:'one',query:'other',expectedTitle:null}];
  assert.throws(()=>validateCases(dataset));
});
