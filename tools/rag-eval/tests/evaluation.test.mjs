import test from 'node:test';
import assert from 'node:assert/strict';
import {validateCases,evaluateCase,summarize} from '../lib.mjs';
test('target ranks and latency use explicit denominators, preserving unknown durations', () => {
  const results=[
    {id:'one',passed:true,dependencyPassed:true,expectedTitle:'A',expectationMet:true,selected:['B','A'],candidates:[{title:'A'}],extraSelectionsForReview:['B'],elapsedMs:10},
    {id:'two',passed:false,dependencyPassed:true,expectedTitle:'A',expectationMet:false,selected:[],candidates:[],extraSelectionsForReview:[],elapsedMs:30},
    {id:'negative',passed:true,dependencyPassed:true,expectedTitle:null,expectationMet:true,selected:[],candidates:[],extraSelectionsForReview:[]}
  ];
  const summary=summarize(results);
  assert.equal(summary.targetMetrics.denominator,2);
  assert.equal(summary.targetMetrics.candidateTargetHitRate,0.5);
  assert.equal(summary.targetMetrics.finalTargetMrr,0.25);
  assert.deepEqual(summary.retrievalLatencyMs,{measuredCases:2,p50:10,p95:30});
  assert.equal(summarize([]).targetMetrics,null);
  assert.equal(summarize([]).retrievalLatencyMs.p95,null);
});
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
