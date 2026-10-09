import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';

test('live evaluation does not silently use a hardcoded login password',()=>{
  const env={...process.env};delete env.RAG_EVAL_PASSWORD;
  const result=spawnSync(process.execPath,[new URL('../cli.mjs',import.meta.url).pathname.replace(/^\/(\w:)/,'$1')],
    {encoding:'utf8',env});
  assert.equal(result.status,2);
  assert.match(result.stderr,/RAG_EVAL_PASSWORD must be supplied/);
});
test('offline scoring rejects corpus drift and separates missing labels',()=>{
  const dir=mkdtempSync(join(tmpdir(),'rag-quality-'));
  try {
    const report=join(dir,'report.json'),labels=join(dir,'labels.json');
    const chunk={documentVersion:'v1',text:'Synthetic fixture, not medical information.',
      contentSha256:createHash('sha256').update('Synthetic fixture, not medical information.').digest('hex')};
    const approved=[{id:'fixture',chunks:[chunk]}];
    const snapshot=createHash('sha256').update(JSON.stringify(approved)).digest('hex');
    const source={corpus:{sha256:snapshot,approved},results:[
      {id:'one',selectedEvidence:[{excerpt:chunk.text,chunkReferences:[chunk]}],candidates:[
        {excerpt:chunk.text,chunkReferences:[chunk],lexicalRank:1,semanticRank:2,fusedScore:.02}]},
      {id:'two',selectedEvidence:[],candidates:[]}]};
    writeFileSync(report,JSON.stringify(source));
    const label={schemaVersion:1,corpusSha256:snapshot,cases:[{id:'one',exhaustive:true,relevant:[chunk]}]};
    const run=()=>spawnSync(process.execPath,[new URL('../quality-cli.mjs',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),report,labels],{encoding:'utf8'});
    writeFileSync(labels,JSON.stringify(label));
    const valid=run();assert.equal(valid.status,0,valid.stderr);
    const result=JSON.parse(valid.stdout);assert.equal(result.unlabelled,1);
    assert.equal(result.unreviewedAnswers,1);assert.equal(result.summary.recallAtK.value,1);
    assert.equal(result.reviewStatus,'UNSPECIFIED');assert.equal(result.split,'UNSPECIFIED');
    assert.equal(result.summary.answerCorrectness.value,null);
    assert.equal(result.summary.retrievalStages.bm25.recallAtK.value,1);
    assert.equal(result.summary.retrievalStages.qdrant.recallAtK.value,1);
    assert.equal(result.summary.retrievalStages.rrf.recallAtK.value,1);
    assert.equal(result.summary.retrievalStages.final.recallAtK.value,1);
    const answerText='A synthetic generated answer, not medical information.';
    const answerSnapshot={text:answerText,messageId:'fixture-message',traceId:'fixture-trace',
      contentSha256:createHash('sha256').update(answerText).digest('hex')};
    const answerReview={correctness:'CORRECT',claims:[{support:'SUPPORTED'}],
      answerSha256:answerSnapshot.contentSha256,answerMessageId:answerSnapshot.messageId,traceId:answerSnapshot.traceId};
    const answerLabels={...label,cases:[{...label.cases[0],answerReview}]};
    writeFileSync(labels,JSON.stringify(answerLabels));
    assert.equal(run().status,1,'A retrieval-only report cannot acquire fabricated answer scores');
    const withAnswer={...source,results:[{...source.results[0],answerSnapshot},source.results[1]]};
    writeFileSync(report,JSON.stringify(withAnswer));
    const answerResult=run();assert.equal(answerResult.status,0,answerResult.stderr);
    assert.equal(JSON.parse(answerResult.stdout).summary.answerCorrectness.value,1);
    for(const override of [{text:'Changed answer'}, {messageId:'different-message'}, {traceId:'different-trace'},
      {contentSha256:'0'.repeat(64)}]) {
      writeFileSync(report,JSON.stringify({...withAnswer,results:[{...withAnswer.results[0],answerSnapshot:{...answerSnapshot,...override}}]}));
      assert.equal(run().status,1,'Changed answer identity cannot reuse review');
    }
    writeFileSync(report,JSON.stringify(source));writeFileSync(labels,JSON.stringify(label));
    assert.deepEqual(result.cases[0].missed,[]);
    assert.deepEqual(result.cases[0].extra,[]);
    assert.equal(result.cases[0].retrieval.hits,1);
    assert.deepEqual(result.cases[1],{id:'two',status:'UNLABELLED'});
    const unrelated={...chunk,text:'A separate synthetic fixture.',
      contentSha256:createHash('sha256').update('A separate synthetic fixture.').digest('hex')};
    const expanded=[{id:'fixture',chunks:[chunk,unrelated]}];
    const expandedHash=createHash('sha256').update(JSON.stringify(expanded)).digest('hex');
    writeFileSync(report,JSON.stringify({corpus:{sha256:expandedHash,approved:expanded},results:[
      {id:'one',selectedEvidence:[{excerpt:unrelated.text,chunkReferences:[unrelated]}]}]}));
    writeFileSync(labels,JSON.stringify({...label,corpusSha256:expandedHash}));
    const missed=JSON.parse(run().stdout);
    assert.deepEqual(missed.cases[0].missed,[chunk]);
    assert.deepEqual(missed.cases[0].extra,[unrelated]);
    assert.equal(missed.cases[0].retrieval.recallAtK,0);
    writeFileSync(report,JSON.stringify(source));
    writeFileSync(labels,JSON.stringify({...label,corpusSha256:'old'}));
    assert.equal(run().status,1);
    writeFileSync(labels,JSON.stringify({...label,cases:[{id:'one',exhaustive:true,
      relevant:[{...chunk,contentSha256:'a'.repeat(64)}]}]}));
    assert.equal(run().status,1,'Invented relevant identities cannot produce quality scores');
    writeFileSync(labels,JSON.stringify(label));
    writeFileSync(report,JSON.stringify({...source,corpus:{...source.corpus,approved:[]}}));
    assert.equal(run().status,1,'Modified snapshots cannot retain the old fingerprint');
    writeFileSync(report,JSON.stringify({...source,results:[{id:'one',selectedEvidence:[
      {excerpt:'A different excerpt cannot borrow a relevant identity.',chunkReferences:[chunk]}]}]}));
    assert.equal(run().status,1,'Selected excerpts cannot borrow another chunk identity');
  } finally {rmSync(dir,{recursive:true,force:true});}
});
