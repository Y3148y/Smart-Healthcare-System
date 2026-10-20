import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {compareRetrievalRuns} from '../ablation.mjs';

const sha=value=>createHash('sha256').update(value).digest('hex');
function fixture() {
  const chunks=['alpha synthetic','beta synthetic','gamma synthetic'].map((text,i)=>({
    chunkId:`chunk-${i}`,documentVersion:`v-${i}`,text,source:`source-${i}`,contentSha256:sha(text),
  }));
  const approved=chunks.map((chunk,i)=>({id:`doc-${i}`,title:`title-${i}`,chunks:[chunk]}));
  const evidence=i=>({title:approved[i].title,source:chunks[i].source,excerpt:chunks[i].text,chunkReferences:[chunks[i]]});
  const candidates=[{...evidence(0),lexicalRank:1,semanticRank:0},
    {...evidence(1),lexicalRank:2,semanticRank:2},{...evidence(2),lexicalRank:0,semanticRank:1}];
  const report={corpus:{approved,sha256:sha(JSON.stringify(approved))},summary:{corpusStable:true},
    runtime:{mode:'synthetic'},results:[{id:'one',query:'synthetic query',candidates,selectedEvidence:[evidence(0)],
      lexicalStatus:'MEMORY_BM25_READY',semanticStatus:'READY',rerankStatus:'DISABLED_BY_CONFIGURATION'}]};
  const labels={schemaVersion:1,corpusSha256:report.corpus.sha256,reviewStatus:'AUTHOR_ENGINEERING_LABELS',
    cases:[{id:'one',query:'synthetic query',exhaustive:true,relevant:[chunks[1]]}]};
  return {report,labels,chunks,evidence};
}

test('offline RRF replay uses reciprocal ranks and never treats missing labels as perfect',()=>{
  const {report,chunks}=fixture();
  const result=compareRetrievalRuns([report],null,{k:1});
  const run=result.runs[0],rankings=run.cases[0].rankings;
  assert.deepEqual(rankings.bm25.identities,[`${chunks[0].documentVersion}:${chunks[0].contentSha256}`]);
  for (const constant of [10,30,60,100]) assert.deepEqual(rankings[`rrf_${constant}_pre_gates`].identities,
    [`${chunks[1].documentVersion}:${chunks[1].contentSha256}`]);
  assert.equal(run.strategies.bm25.recallAtK.value,null);
  assert.equal(run.strategies.bm25.recallAtK.evaluated,0);
  assert.equal(rankings.captured_final.quality,null);
  assert.equal(run.unlabelledCases,1);
  assert.equal(result.review.reviewerType,'UNSPECIFIED');
  assert.equal(JSON.stringify(result).includes('synthetic query'),false,'Do not copy patient queries to comparison output');
  assert.equal(JSON.stringify(result).includes('alpha synthetic'),false,'Do not duplicate corpus text');
});

test('RRF comparisons preserve AI-assisted label provenance rather than silently implying human gold',()=>{
  const {report,labels}=fixture();
  const aiLabels={...labels,reviewStatus:'AI_ASSISTED_REVIEWED',reviewerType:'AI_ASSISTED'};
  const result=compareRetrievalRuns([report],aiLabels);
  assert.equal(result.review.reviewerType,'AI_ASSISTED');assert.match(result.review.reviewNotice,/not human gold/);
  assert.throws(()=>compareRetrievalRuns([report],{...aiLabels,reviewerType:'HUMAN'}));
});

test('query-bound exhaustive labels score routes independently and compare exact matching runs',()=>{
  const {report,labels,evidence}=fixture();
  const other=structuredClone(report);
  other.results[0].selectedEvidence=[evidence(1)];
  other.results[0].lexicalStatus='ELASTICSEARCH_READY';
  const result=compareRetrievalRuns([report,other],labels,{k:1});
  assert.equal(result.runs[0].strategies.bm25.recallAtK.value,0);
  assert.equal(result.runs[0].strategies.rrf_60_pre_gates.recallAtK.value,1);
  assert.equal(result.runs[0].strategies.captured_final.recallAtK.value,0);
  assert.equal(result.runs[1].strategies.captured_final.recallAtK.value,1);
  assert.equal(result.differences[0].finalTopKChanged,1);
  assert.equal(result.runs[1].cases[0].dependencies.lexical,'ELASTICSEARCH_READY');
});

test('RRF K grid can change ranking without changing captured route ranks',()=>{
  const {report,chunks}=fixture();
  report.results[0].candidates[1].lexicalRank=20;
  report.results[0].candidates[1].semanticRank=20;
  const rankings=compareRetrievalRuns([report],null,{k:1}).runs[0].cases[0].rankings;
  assert.deepEqual(rankings.rrf_10_pre_gates.identities,[`${chunks[0].documentVersion}:${chunks[0].contentSha256}`]);
  assert.deepEqual(rankings.rrf_60_pre_gates.identities,[`${chunks[1].documentVersion}:${chunks[1].contentSha256}`]);
});

test('drift, ambiguous identities, forged excerpts and route ranks are rejected',()=>{
  for (const mutate of [
    report=>{report.corpus.approved[0].chunks[0].text='drift';},
    report=>{report.summary.corpusStable=false;},
    report=>{report.results[0].candidates[0].excerpt='forged';},
    report=>{report.results[0].candidates[0].chunkReferences=[];},
    report=>{report.results[0].candidates[0].source='other';},
    report=>{report.results[0].candidates[1].lexicalRank=1;},
    report=>{report.results[0].candidates[0].semanticRank=-1;},
    report=>{report.results.push(report.results[0]);},
  ]) {
    const {report}=fixture();mutate(report);
    assert.throws(()=>compareRetrievalRuns([report]));
  }
  const {report}=fixture(), other=structuredClone(report);
  other.results[0].query='different query';
  assert.throws(()=>compareRetrievalRuns([report,other]),/identical/);
});

test('labels cannot borrow case ids, queries or identities; uncovered selections remain visible',()=>{
  for (const mutate of [
    labels=>{labels.corpusSha256='old';},labels=>{labels.cases[0].exhaustive=false;},
    labels=>{labels.cases[0].query='other';},labels=>{labels.cases[0].id='missing';},
    labels=>{labels.cases[0].relevant[0].documentVersion='missing';},
  ]) {
    const {report,labels}=fixture();mutate(labels);
    assert.throws(()=>compareRetrievalRuns([report],labels));
  }
  const {report,labels}=fixture();labels.cases[0].relevant=[];
  const result=compareRetrievalRuns([report],labels);
  assert.equal(result.runs[0].strategies.captured_final.noCoverageFalseSelections,1);
  assert.equal(result.runs[0].strategies.captured_final.recallAtK.value,null);
});
