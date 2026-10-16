import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';

test('retrieval review CLI confines source text to ignored reports and refuses overwrite and excess arguments',()=>{
  const folder=mkdtempSync(join(tmpdir(),'rag-review-path-'));
  const output=resolve(fileURLToPath(new URL('../../../reports/rag/',import.meta.url)),`synthetic-review-${randomUUID()}.json`);
  const cli=fileURLToPath(new URL('../review-packet-cli.mjs',import.meta.url));
  try {
    const input=join(folder,'input.json');
    writeFileSync(input,JSON.stringify({corpus:{sha256:'synthetic',approved:[{id:'fixture',title:'fixture',
      chunks:[{chunkId:'one',text:'Synthetic source text, not medical material'}]}]},results:[{id:'one',query:'Synthetic query',selectedEvidence:[]}]}));
    const run=(...args)=>spawnSync(process.execPath,[cli,...args],{encoding:'utf8'});
    const outside=join(folder,'outside.json');
    assert.equal(run(input,outside).status,1);
    assert.equal(existsSync(outside),false);
    const success=run(input,output);
    assert.equal(success.status,0,success.stderr);
    assert.equal(JSON.parse(readFileSync(output,'utf8')).reviewStatus,'UNREVIEWED');
    const before=readFileSync(output,'utf8');
    assert.equal(run(input,output).status,1);
    assert.equal(readFileSync(output,'utf8'),before);
    assert.equal(run(input,output,'--unknown').status,1);
  } finally { rmSync(output,{force:true});rmSync(folder,{recursive:true,force:true}); }
});
