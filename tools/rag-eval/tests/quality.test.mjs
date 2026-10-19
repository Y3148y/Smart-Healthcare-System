import test from 'node:test';
import assert from 'node:assert/strict';
import {retrievalQuality,summarizeQuality,summarizeRetrievalStages,confidenceCalibration} from '../quality.mjs';
const chunk=n=>({documentVersion:'version-1',contentSha256:String(n).padStart(64,'0')});
test('recall and precision require exhaustive versioned labels, not title matching',()=>{
  const r=retrievalQuality([chunk(2),chunk(3),chunk(1)],{exhaustive:true,relevant:[chunk(1),chunk(2)]},2);
  assert.equal(r.recallAtK,.5);assert.equal(r.precisionAtK,.5);assert.equal(r.reciprocalRank,1);
  assert.throws(()=>retrievalQuality([],{exhaustive:false,relevant:[]},3));
  assert.throws(()=>retrievalQuality([{title:'same'}],{exhaustive:true,relevant:[]},3));
  assert.equal(retrievalQuality([{...chunk(1),documentVersion:'old'}],
    {exhaustive:true,relevant:[chunk(1)]},3).hits,0);
});
test('duplicates, uncovered and empty results cannot inflate quality',()=>{
  assert.equal(retrievalQuality([chunk(1),chunk(1)],{exhaustive:true,relevant:[chunk(1)]},3).hits,1);
  const empty=retrievalQuality([],{exhaustive:true,relevant:[chunk(1)]},3);
  assert.equal(empty.recallAtK,0);assert.equal(empty.precisionAtK,null);
  const negative=retrievalQuality([chunk(2)],{exhaustive:true,relevant:[]},3);
  assert.equal(negative.recallAtK,null);assert.equal(negative.precisionAtK,0);
  assert.equal(negative.noCoverageFalseSelection,true);
});
test('summary aggregates already-scored answers without counting unknown values',()=>{
  const summary=summarizeQuality([],[{correct:false,faithfulness:.5},{correct:null,faithfulness:null}]);
  assert.deepEqual(summary.answerCorrectness,{value:0,evaluated:1});
  assert.deepEqual(summary.faithfulness,{value:.5,evaluated:1});
});
test('retrieval-only summary never fabricates answer correctness or faithfulness',()=>{
  const result=summarizeQuality([],[]);
  assert.deepEqual(result.answerCorrectness,{value:null,evaluated:0});
  assert.deepEqual(result.faithfulness,{value:null,evaluated:0});
});
test('retrieval stages are measured independently and no-coverage false selections remain visible',()=>{
  const labels={exhaustive:true,relevant:[chunk(1)]};
  const stages={bm25:retrievalQuality([chunk(2)],labels,1),qdrant:retrievalQuality([chunk(1)],labels,1),
    rrf:retrievalQuality([chunk(1)],labels,1),final:retrievalQuality([chunk(2)],labels,1)};
  const summary=summarizeRetrievalStages([{stages}]);
  assert.equal(summary.bm25.recallAtK.value,0);assert.equal(summary.qdrant.recallAtK.value,1);
  assert.equal(summary.rrf.mrr.value,1);assert.equal(summary.final.recallAtK.value,0);
  const noCoverage=Object.fromEntries(Object.keys(stages).map(key=>[key,retrievalQuality([chunk(2)],{exhaustive:true,relevant:[]},1)]));
  assert.equal(summarizeRetrievalStages([{stages:noCoverage}]).bm25.noCoverageFalseSelections,1);
});
test('confidence calibration is unknown without independently judged dispositions',()=>{
  const empty=confidenceCalibration([]);
  assert.equal(empty.evaluated,0);assert.equal(empty.brierScore,null);assert.equal(empty.expectedCalibrationError,null);
  const measured=confidenceCalibration([{confidence:80,correctness:'CORRECT'},{confidence:80,correctness:'INCORRECT'}]);
  assert.equal(measured.evaluated,2);assert.ok(Math.abs(measured.brierScore-.34)<1e-12);
  assert.ok(Math.abs(measured.expectedCalibrationError-.3)<1e-12);assert.equal(measured.bins[0].range,'80-100%');
});
