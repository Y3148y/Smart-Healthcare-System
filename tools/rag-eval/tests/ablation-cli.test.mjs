import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';

test('ablation CLI is offline, confines output and refuses overwrites or malformed options',()=>{
  const folder=mkdtempSync(join(tmpdir(),'rag-ablation-'));
  const output=resolve(fileURLToPath(new URL('../../../reports/rag/',import.meta.url)),`synthetic-ablation-${randomUUID()}.json`);
  const cli=fileURLToPath(new URL('../ablation-cli.mjs',import.meta.url));
  const hash=value=>createHash('sha256').update(value).digest('hex');
  try {
    const text='synthetic fixture';
    const chunk={documentVersion:'v1',text,source:'fixture',contentSha256:hash(text)};
    const approved=[{title:'fixture',chunks:[chunk]}];
    const evidence={title:'fixture',source:'fixture',excerpt:text,chunkReferences:[chunk]};
    const report={summary:{corpusStable:true},corpus:{approved,sha256:hash(JSON.stringify(approved))},results:[
      {id:'one',query:'fixture query',candidates:[{...evidence,lexicalRank:1,semanticRank:0}],selectedEvidence:[evidence]}]};
    const input=join(folder,'input.json');writeFileSync(input,JSON.stringify(report));
    const run=(...args)=>spawnSync(process.execPath,[cli,...args],{encoding:'utf8'});
    const success=run(input,'--output',output);
    assert.equal(success.status,0,success.stderr);
    assert.equal(JSON.parse(readFileSync(output,'utf8')).runs[0].labelledCases,0);
    assert.equal(run(input,'--output',output).status,1,'Existing result must not be overwritten');
    const outside=join(folder,'outside.json');
    assert.equal(run(input,'--output',outside).status,1);
    assert.equal(existsSync(outside),false);
    assert.equal(run(input,'--labels','--output',output).status,1);
    assert.equal(run(input,'--output',output,'--k','--k','3').status,1);
    assert.equal(run(input,'--output',output,'--unknown').status,1);
  } finally {
    rmSync(output,{force:true});rmSync(folder,{recursive:true,force:true});
  }
});
