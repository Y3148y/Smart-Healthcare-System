import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {mkdirSync,writeFileSync,readFileSync,unlinkSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash,randomUUID} from 'node:crypto';

test('real CLI accepts a local frozen dataset and fails a run whose publication or observed model changes',async()=>{
  const directory=resolve(fileURLToPath(new URL('../../../reports/rag/',import.meta.url)));
  mkdirSync(directory,{recursive:true});
  const path=resolve(directory,'synthetic-evaluation-'+randomUUID()+'.json');
  const source='Synthetic source paragraph',hash=createHash('sha256').update(source).digest('hex');
  const bytes=JSON.stringify({schemaVersion:1,labelVersion:'synthetic-evaluation',reviewStatus:'NOT_CLINICALLY_REVIEWED',
    cases:[{id:'one',query:'synthetic question',expectedTitle:'Fixture'}]});
  writeFileSync(path,bytes,{flag:'wx'});
  let variant='stable',runtimeReads=0,publicationReads=0,corpusReads=0;
  const server=createServer((req,res)=>{
    req.resume();
    const route=new URL(req.url,'http://localhost').pathname;
    let result;
    if(route==='/api/auth/login') result={token:'synthetic'};
    else if(route.endsWith('/retrieval-snapshot')) result={scope:'ACTIVE_RETRIEVAL',sourceRevision:1,publicationVersion:2,
      documents:[{id:'d',title:++corpusReads===2 && variant==='corpus'?'Changed':'Fixture',chunks:[{
        chunkId:'c',documentVersion:'v4',chunkingVersion:'synthetic-v4',contentSha256:hash,text:source,source:'synthetic'}]}]};
    else if(route.endsWith('/chunks')) result=[{chunkId:'c',documentVersion:'v4',chunkingVersion:'synthetic-v4',
      contentSha256:hash,text:source,source:'synthetic'}];
    else if(route.endsWith('/runtime')) result={embeddingModel:++runtimeReads===2 && variant==='model'?'changed':'fixture',rerankEnabled:'false'};
    else if(route.endsWith('/releases')) result={state:{pointerVersion:++publicationReads===2 && variant==='release'?3:2,
      activeReleaseId:'fixed',mode:'VERSIONED'}};
    else if(route.endsWith('/search/details')) result={semanticStatus:'READY',rerankStatus:'DISABLED_BY_CONFIGURATION',
      mode:'HYBRID_QDRANT_UNRERANKED',candidates:[],retrieval:{evidence:[{title:'Fixture',excerpt:source}]}};
    else {res.writeHead(404);res.end();return;}
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(result));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    for(variant of ['stable','model','release','corpus']) {
      runtimeReads=0;publicationReads=0;corpusReads=0;
      const child=spawn(process.execPath,[fileURLToPath(new URL('../cli.mjs',import.meta.url)),'--dataset',path],{
        env:{...process.env,RAG_EVAL_PASSWORD:'synthetic',RAG_EVAL_BASE_URL:`http://127.0.0.1:${server.address().port}/api/`}});
      let output='',errors='';child.stdout.on('data',value=>output+=value);child.stderr.on('data',value=>errors+=value);
      const exit=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',resolve);});
      assert.equal(exit,variant==='stable'?0:1,errors);
      const receipt=JSON.parse(output);
      assert.equal(dirname(resolve(receipt.output)),directory);
      try {
        const report=JSON.parse(readFileSync(receipt.output,'utf8'));
        assert.equal(report.datasetSha256,createHash('sha256').update(bytes).digest('hex'));
        assert.equal(report.summary.observedRuntimeStable,variant!=='model');
        assert.equal(report.summary.publicationStable,variant!=='release');
        assert.equal(report.summary.corpusStable,variant!=='corpus');
        assert.equal(report.observations.corpusBeforeSha256,report.corpus.sha256);
        assert.equal(report.observations.corpusBeforeSha256===report.observations.corpusAfterSha256,variant!=='corpus');
      } finally {unlinkSync(receipt.output);}
    }
  } finally {await new Promise(resolve=>server.close(resolve));unlinkSync(path);}
});
