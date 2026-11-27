import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,rmSync,symlinkSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {basename,dirname,join,resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {loadEvaluationInput,runIntegrity} from '../evaluation-input.mjs';

test('explicit report datasets bind exact bytes and reject path escape or malformed options',()=>{
  const folder=mkdtempSync(join(tmpdir(),'evaluation-input-'));
  try {
    const directory=join(folder,'tools'),reportsDirectory=join(folder,'reports');
    mkdirSync(directory);mkdirSync(reportsDirectory);
    const value=JSON.stringify({schemaVersion:1,labelVersion:'fixture',reviewStatus:'NOT_CLINICALLY_REVIEWED',
      cases:[{id:'one',query:'fixture',expectedTitle:null}]});
    const builtin=join(directory,'cases.json'),local=join(reportsDirectory,'local.json'),outside=join(folder,'outside.json');
    for(const path of [builtin,local,outside]) writeFileSync(path,value);
    const options={directory,reportsDirectory};
    assert.equal(loadEvaluationInput([],options).datasetSha256,createHash('sha256').update(value).digest('hex'));
    assert.equal(loadEvaluationInput(['--dataset',local],options).dataset.cases.length,1);
    writeFileSync(local,value+'\n');
    assert.notEqual(loadEvaluationInput(['--dataset',local],options).datasetSha256,loadEvaluationInput([],options).datasetSha256);
    for(const args of [['--dataset',outside],['../outside.json'],['--dataset'],['--all'],['cases.json','extra']])
      assert.throws(()=>loadEvaluationInput(args,options));
    const disguised=join(reportsDirectory,'not-json.md');writeFileSync(disguised,value);
    assert.throws(()=>loadEvaluationInput(['--dataset',disguised],options),/PATH_REQUIRED/);
    const large=join(reportsDirectory,'large.json');writeFileSync(large,' '.repeat(1024*1024+1));
    assert.throws(()=>loadEvaluationInput(['--dataset',large],options),/SIZE/);
    const link=join(reportsDirectory,'escape');symlinkSync(folder,link,'junction');
    assert.throws(()=>loadEvaluationInput(['--dataset',join(link,'outside.json')],options),/PATH_REQUIRED/);
  } finally {
    assert.equal(dirname(resolve(folder)),resolve(tmpdir()));
    assert.ok(basename(folder).startsWith('evaluation-input-'));
    rmSync(folder,{recursive:true,force:true});
  }
});

test('evaluation rejects observed corpus, model, rerank and publication drift but permits last-query status updates',()=>{
  const seed={before:{sha256:'same',sourceRevision:1,publicationVersion:2},after:{sha256:'same',sourceRevision:1,publicationVersion:2},runtimeBefore:{embeddingModel:'one',rerankEnabled:'false',semanticStatus:'NOT_QUERIED'},
    runtimeAfter:{embeddingModel:'one',rerankEnabled:'false',semanticStatus:'READY'},
    publicationBefore:{pointerVersion:2,activeReleaseId:'release',mode:'VERSIONED'},
    publicationAfter:{pointerVersion:2,activeReleaseId:'release',mode:'VERSIONED'}};
  assert.deepEqual(runIntegrity(seed),{corpusStable:true,observedRuntimeStable:true,publicationStable:true});
  assert.equal(runIntegrity({...seed,after:{...seed.after,sha256:'changed'}}).corpusStable,false);
  assert.equal(runIntegrity({...seed,after:{...seed.after,sourceRevision:2}}).corpusStable,false);
  assert.equal(runIntegrity({...seed,after:{...seed.after,publicationVersion:3}}).publicationStable,false);
  for(const change of [{embeddingModel:'two'},{rerankEnabled:'true'},{requireSemantic:'true'}])
    assert.equal(runIntegrity({...seed,runtimeAfter:{...seed.runtimeAfter,...change}}).observedRuntimeStable,false);
  for(const change of [{pointerVersion:3},{activeReleaseId:'other'},{mode:'LEGACY_UNPUBLISHED'}])
    if(change.mode) assert.equal(runIntegrity({...seed,publicationAfter:{pointerVersion:2,activeReleaseId:null,...change}}).publicationStable,false);
    else assert.equal(runIntegrity({...seed,publicationAfter:{...seed.publicationAfter,...change}}).publicationStable,false);
  for(const state of [{},{pointerVersion:-1,activeReleaseId:'r',mode:'VERSIONED'},
      {pointerVersion:2,activeReleaseId:null,mode:'VERSIONED'}])
    assert.throws(()=>runIntegrity({...seed,publicationAfter:state}),/PUBLICATION/);
  assert.throws(()=>runIntegrity({...seed,runtimeAfter:{}}),/RUNTIME/);
});
