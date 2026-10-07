import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
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
    {...report,retrieval:{evidence:[{title:'Fixture'},{title:'Fixture'},{title:'Other'}]}});
  assert.equal(result.passed,true);
  assert.deepEqual(result.selected,['Fixture','Other']);
  assert.deepEqual(result.extraSelectionsForReview,['Other']);
});
test('candidate report retains exact excerpt and matching corpus chunk identity for review', () => {
  const excerpt='胸痛资料的核验片段';
  const contentSha256=createHash('sha256').update(excerpt).digest('hex');
  const result=evaluateCase({id:'trace',query:'胸痛',expectedTitle:'Chest'}, {
    ...report,
    corpus:{approved:[{title:'Chest',chunks:[{chunkId:'chunk-1',documentVersion:'v1',contentSha256,chunkingVersion:'v3'}]}]},
    candidates:[{title:'Chest',source:'https://example.invalid/source',excerpt,kept:true}],
    retrieval:{evidence:[{title:'Chest',source:'https://example.invalid/source',excerpt,score:0.8}]},
  });
  assert.equal(result.candidates[0].excerpt,excerpt);
  assert.equal(result.candidates[0].chunkReferences[0].chunkId,'chunk-1');
  assert.equal(result.selectedEvidence[0].excerpt,excerpt);
});
test('missing negative label and duplicate IDs fail dataset validation', () => {
  const dataset={schemaVersion:1,labelVersion:'fixture',reviewStatus:'NOT_CLINICALLY_REVIEWED',cases:[{id:'one',query:'fixture'}]};
  assert.throws(()=>validateCases(dataset));
  dataset.cases=[{id:'one',query:'fixture',expectedTitle:null},{id:'one',query:'other',expectedTitle:null}];
  assert.throws(()=>validateCases(dataset));
});
