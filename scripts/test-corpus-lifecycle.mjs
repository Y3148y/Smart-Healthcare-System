// Explicit isolated-test probe. Synthetic vectors check lifecycle mechanics, not relevance.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join,resolve} from 'node:path';
import {localTestTarget} from './local-test-target.cjs';
import {approveTestDocument,readTestCorpus,TEST_EVIDENCE} from '../tools/knowledge-sync/lib/test-corpus-approval.mjs';

export const MODEL='synthetic-corpus-lifecycle-v1';

export function configuration(env,args) {
  const base=localTestTarget(env.CORPUS_TEST_BASE_URL,args);
  if(args.length!==1 || args[0]!=='--apply' || env.CORPUS_TEST_ISOLATED!=='YES'
      || env.AI_MODE!=='demo' || env.AI_AGENT_MODE!=='OFF' || !env.CORPUS_TEST_PASSWORD)
    throw new Error('EXPLICIT_ISOLATED_DEMO_REQUIRED');
  return {base,password:env.CORPUS_TEST_PASSWORD};
}

export function embeddingFixture() {
  return createServer(async(req,res)=>{
    try {
      if(req.method!=='POST' || req.url!=='/embeddings') {res.writeHead(404).end();return;}
      let body='';
      for await(const chunk of req) {
        body+=chunk;
        if(Buffer.byteLength(body)>1024*1024) throw new Error('TOO_LARGE');
      }
      const value=JSON.parse(body);
      if(value.model!==MODEL || !Array.isArray(value.input) || !value.input.length
          || value.input.length>100 || value.input.some(text=>typeof text!=='string')) throw new Error('INVALID');
      // Deliberately identical: the fixture must never imply medically meaningful similarity.
      res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({
        data:value.input.map((_text,index)=>({index,embedding:[1,0]})),
      }));
    } catch {res.writeHead(400,{'Content-Type':'application/json'}).end('{"error":"INVALID_FIXTURE_REQUEST"}');}
  });
}

function corpusPaths(dir) {
  return readdirSync(dir,{withFileTypes:true}).flatMap(entry=>{
    const path=join(dir,entry.name);
    return entry.isDirectory()?corpusPaths(path):entry.isFile() && entry.name==='document.zh-CN.md'?[path]:[];
  }).sort();
}

export async function lifecycle(config,{fetcher=fetch,packets,onStage=()=>{}}={}) {
  const request=async(method,path,token,body)=>{
    const response=await fetcher(config.base+'/api/'+path,{method,redirect:'error',
      headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
      ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(45000)});
    assert.equal(response.status,200,'Unexpected lifecycle HTTP status');
    return response.json();
  };
  onStage('EMPTY_INSTANCE_GUARD');
  const token=(await request('POST','auth/login',null,{username:'admin',password:config.password})).token;
  assert.equal(typeof token,'string');assert.ok(token);
  const runtime=await request('GET','admin/knowledge/runtime',token);
  assert.equal(runtime.embeddingModel,MODEL,'Only the local synthetic fixture is supported');
  assert.equal(runtime.embeddingConfigured,'true');assert.equal(runtime.rerankEnabled,'false');
  // Reject existing data before the first write. Operators must still verify DB/index isolation.
  assert.deepEqual(await request('GET','admin/knowledge',token),[],'Use an empty isolated database');
  const source=packets??readTestCorpus(corpusPaths(fileURLToPath(new URL('../knowledge/corpus/',import.meta.url))));
  assert.equal(source.length,11);
  onStage('IMPORT_AND_TEST_APPROVAL');
  for(const packet of source) await approveTestDocument(packet,{base:config.base+'/api/',token,fetcher});
  const documents=await request('GET','admin/knowledge',token);
  assert.equal(documents.length,11);
  let chunks=0;
  const identities=new Map();
  for(const packet of source) {
    const doc=documents.find(item=>item.title===packet.title && item.body===packet.body);
    assert.ok(doc);assert.equal(doc.status,'READY');
    const metadata=await request('GET',`admin/knowledge/${encodeURIComponent(doc.id)}/metadata`,token);
    assert.equal(metadata.clinicalReview,'NOT_REVIEWED');
    assert.equal(metadata.metadata.permissionEvidence,TEST_EVIDENCE);
    assert.equal(metadata.metadata.intakeAudit.contentSha256,packet.contentSha256);
    const rows=await request('GET',`admin/knowledge/${encodeURIComponent(doc.id)}/chunks`,token);
    identities.set(doc.id,rows.map(row=>`${row.chunkId}:${row.documentVersion}:${row.contentSha256}`).sort());
    assert.equal(new Set(identities.get(doc.id)).size,rows.length);
    chunks+=rows.length;
  }
  assert.equal(chunks,78,'Update the documented corpus snapshot before changing expected chunk count');
  onStage('INDEX_AND_HYBRID_QUERY');
  const sync=await request('POST','admin/knowledge/index/sync',token);
  assert.equal(sync.success,'true');
  for(const doc of documents) {
    const detail=await request('GET',`admin/knowledge/${encodeURIComponent(doc.id)}/details`,token);
    assert.equal(detail.indexStatus,'INDEXED_IN_PROCESS');
    assert.equal(detail.indexedChunks,identities.get(doc.id).length);
    assert.equal(detail.segments.length,detail.indexedChunks);
  }
  let queried=0;
  for(const packet of source) {
    const report=await request('GET','admin/knowledge/search/details?q='+encodeURIComponent(packet.title),token);
    assert.equal(report.semanticStatus,'READY');
    assert.equal(report.lexicalStatus,'ELASTICSEARCH_READY');
    assert.ok(report.candidates.some(item=>item.semanticRank>0));
    assert.ok(report.candidates.some(item=>item.lexicalRank>0));
    assert.ok(report.candidates.some(item=>item.lexicalRank>0 && item.title===packet.title));
    // Identical synthetic vectors cannot establish which final excerpts are relevant.
    assert.ok(report.retrieval.evidence.length>0);
    queried++;
  }
  // Existing import/approval command must replay without creating duplicate records/chunks.
  onStage('IDEMPOTENT_REPLAY');
  for(const packet of source) await approveTestDocument(packet,{base:config.base+'/api/',token,fetcher});
  assert.equal((await request('GET','admin/knowledge',token)).length,11);
  for(const doc of documents) {
    const rows=await request('GET',`admin/knowledge/${encodeURIComponent(doc.id)}/chunks`,token);
    assert.deepEqual(rows.map(row=>`${row.chunkId}:${row.documentVersion}:${row.contentSha256}`).sort(),identities.get(doc.id));
  }
  onStage('WITHDRAWAL_AND_EMPTY_CORPUS');
  for(const doc of documents) {
    assert.equal((await request('POST',`admin/knowledge/${encodeURIComponent(doc.id)}/withdraw`,token)).status,'PENDING_REVIEW');
  }
  const withdrawn=await request('GET','admin/knowledge',token);
  assert.equal(withdrawn.length,11);
  assert.ok(withdrawn.every(doc=>doc.status==='PENDING_REVIEW'));
  for(const packet of source) {
    const report=await request('GET','admin/knowledge/search/details?q='+encodeURIComponent(packet.title),token);
    assert.deepEqual(report.retrieval.evidence,[]);
    assert.equal(report.retrieval.grounded,false);
    assert.equal(report.semanticStatus,'EMPTY_CORPUS');
    assert.equal(report.lexicalStatus,'ELASTICSEARCH_EMPTY_CORPUS');
    assert.deepEqual(report.candidates,[]);
  }
  return {status:'PASSED_SYNTHETIC_LIFECYCLE',documents:11,chunks,hybridQueries:queried,
    withdrawals:11,embedding:MODEL,clinicalReview:'NOT_REVIEWED',qualityMetrics:null};
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  let stage='CONFIGURATION';
  try {
    if(process.argv.length===3 && process.argv[2]==='--serve-embedding') {
      const server=embeddingFixture();
      server.listen(0,'127.0.0.1',()=>console.log(JSON.stringify({model:MODEL,baseUrl:'http://127.0.0.1:'+server.address().port})));
      for(const signal of ['SIGINT','SIGTERM']) process.once(signal,()=>server.close());
    } else console.log(JSON.stringify(await lifecycle(configuration(process.env,process.argv.slice(2)),{
      onStage:value=>{stage=value;},
    })));
  } catch {console.error(JSON.stringify({status:'CORPUS_LIFECYCLE_FAILED',stage}));process.exitCode=1;}
}
