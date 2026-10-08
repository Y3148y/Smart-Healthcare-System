import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
test('offline scoring rejects corpus drift and separates missing labels',()=>{
  const dir=mkdtempSync(join(tmpdir(),'rag-quality-'));
  try {
    const report=join(dir,'report.json'),labels=join(dir,'labels.json');
    const chunk={documentVersion:'v1',contentSha256:'a'.repeat(64)};
    writeFileSync(report,JSON.stringify({corpus:{sha256:'snapshot'},results:[
      {id:'one',selectedEvidence:[{chunkReferences:[chunk]}]},{id:'two',selectedEvidence:[]}]}));
    const label={schemaVersion:1,corpusSha256:'snapshot',cases:[{id:'one',exhaustive:true,relevant:[chunk]}]};
    const run=()=>spawnSync(process.execPath,[new URL('../quality-cli.mjs',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),report,labels],{encoding:'utf8'});
    writeFileSync(labels,JSON.stringify(label));
    const valid=run();assert.equal(valid.status,0,valid.stderr);
    const result=JSON.parse(valid.stdout);assert.equal(result.unlabelled,1);
    assert.equal(result.unreviewedAnswers,1);assert.equal(result.summary.recallAtK.value,1);
    assert.equal(result.summary.answerCorrectness.value,null);
    writeFileSync(labels,JSON.stringify({...label,corpusSha256:'old'}));
    assert.equal(run().status,1);
  } finally {rmSync(dir,{recursive:true,force:true});}
});
