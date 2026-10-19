import {test} from 'node:test';
import assert from 'node:assert/strict';
import {configuration,embeddingFixture,lifecycle,MODEL} from './test-corpus-lifecycle.mjs';
import {readdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {readTestCorpus} from '../tools/knowledge-sync/lib/test-corpus-approval.mjs';

test('lifecycle refuses implicit, nonloopback and non-demo targets before requests',()=>{
  const env={CORPUS_TEST_BASE_URL:'http://127.0.0.1:49001',CORPUS_TEST_ISOLATED:'YES',
    AI_MODE:'demo',AI_AGENT_MODE:'OFF',CORPUS_TEST_PASSWORD:'synthetic-only'};
  assert.equal(configuration(env,['--apply']).base,env.CORPUS_TEST_BASE_URL);
  for(const patch of [{CORPUS_TEST_BASE_URL:undefined},{CORPUS_TEST_BASE_URL:'https://example.org'},
    {CORPUS_TEST_ISOLATED:''},{AI_MODE:'openai-compatible'},{AI_AGENT_MODE:'ENABLED'},{CORPUS_TEST_PASSWORD:''}])
    assert.throws(()=>configuration({...env,...patch},['--apply']));
  assert.throws(()=>configuration(env,[]));
  assert.throws(()=>configuration(env,['--apply','unexpected']));
});

test('existing documents or real embedding model are refused before any knowledge write',async()=>{
  for(const [model,docs] of [[MODEL,[{id:'existing'}]],['real-model',[]]]) {
    const writes=[];
    const fetcher=async(url,options)=>{
      if(url.endsWith('/auth/login')) return {status:200,json:async()=>({token:'fixture-token'})};
      if(options.method!=='GET') writes.push(url);
      return {status:200,json:async()=>url.endsWith('/runtime')?
        {embeddingModel:model,embeddingConfigured:'true',rerankEnabled:'false'}:docs};
    };
    await assert.rejects(()=>lifecycle({base:'http://127.0.0.1:49001',password:'fixture'},{fetcher}));
    assert.deepEqual(writes,[]);
  }
});

test('local fixture preserves response indices but provides no meaningful semantic ranking',async()=>{
  const server=embeddingFixture();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  try {
    const response=await fetch(base+'/embeddings',{method:'POST',body:JSON.stringify({model:MODEL,input:['one','unrelated']})});
    assert.equal(response.status,200);
    assert.deepEqual((await response.json()).data,[{index:0,embedding:[1,0]},{index:1,embedding:[1,0]}]);
    const invalid=await fetch(base+'/embeddings',{method:'POST',body:JSON.stringify({model:'other',input:['one']})});
    assert.equal(invalid.status,400);
    assert.equal((await fetch(base+'/chat/completions',{method:'POST'})).status,404);
  } finally {await new Promise(resolve=>server.close(resolve));}
});

test('lifecycle checks complete indexes, replayed chunk identities and genuine withdrawal state',async()=>{
  const root=fileURLToPath(new URL('../knowledge/corpus/',import.meta.url));
  const packets=readTestCorpus(readdirSync(root,{recursive:true}).filter(name=>name.endsWith('document.zh-CN.md'))
    .map(name=>join(root,name)).sort());
  for(const fault of [null,'WITHDRAWAL_DEPENDENCY','REPLAY_CHUNK_CHANGE','INCOMPLETE_INDEX']) {
    const docs=new Map(),metadata=new Map(),chunkLists=new Map();let replay=false;
    const fetcher=async(url,options)=>{
      const parsed=new URL(url);assert.equal(parsed.origin,'http://127.0.0.1:49001');
      const path=parsed.pathname.replace('/api/',''),body=options.body?JSON.parse(options.body):null;
      let value;
      if(path==='auth/login')value={token:'fixture-token'};
      else if(path==='admin/knowledge/runtime')value={embeddingModel:MODEL,embeddingConfigured:'true',rerankEnabled:'false'};
      else if(path==='admin/knowledge')value=[...docs.values()];
      else if(path==='admin/knowledge/documents') {
        let doc=[...docs.values()].find(item=>item.title===body.title);
        if(doc)replay=true;
        else {
          const id='fixture-'+docs.size;
          doc={id,title:body.title,body:body.body,status:'PENDING_REVIEW'};docs.set(id,doc);metadata.set(id,body.metadata);
          chunkLists.set(id,Array.from({length:docs.size===11?8:7},(_item,i)=>({chunkId:id+'-'+i,documentVersion:'v1',contentSha256:'hash-'+i})));
        }
        value=doc;
      } else if(path==='admin/knowledge/index/sync')value={success:'true'};
      else if(path==='admin/knowledge/search/details') {
        const empty=[...docs.values()].every(doc=>doc.status==='PENDING_REVIEW');
        value={semanticStatus:empty?'EMPTY_CORPUS':'READY',lexicalStatus:empty?'ELASTICSEARCH_EMPTY_CORPUS':'ELASTICSEARCH_READY',
          candidates:empty?[]:[{lexicalRank:1,semanticRank:1,title:parsed.searchParams.get('q')}],
          retrieval:{evidence:empty?[]:[{title:'synthetic result'}],grounded:!empty}};
        if(empty && fault==='WITHDRAWAL_DEPENDENCY')value.semanticStatus='INDEX_UNAVAILABLE';
      } else {
        const match=/^admin\/knowledge\/(fixture-\d+)\/(details|metadata|chunks|approve|withdraw)$/.exec(path);
        assert.ok(match,'Unexpected synthetic request');const [,id,action]=match,doc=docs.get(id),rows=chunkLists.get(id);
        if(action==='metadata') {
          if(options.method==='PUT')metadata.set(id,body);
          value={metadata:metadata.get(id),clinicalReview:'NOT_REVIEWED'};
        } else if(action==='details')value={document:doc,segments:rows,indexStatus:'INDEXED_IN_PROCESS',
          indexedChunks:rows.length-(fault==='INCOMPLETE_INDEX'?1:0)};
        else if(action==='chunks')value=replay && fault==='REPLAY_CHUNK_CHANGE'?[...rows,{chunkId:'added',documentVersion:'v1',contentSha256:'new'}]:rows;
        else {doc.status=action==='approve'?'READY':'PENDING_REVIEW';value=doc;}
      }
      return {status:200,ok:true,json:async()=>structuredClone(value)};
    };
    const run=()=>lifecycle({base:'http://127.0.0.1:49001',password:'synthetic'}, {fetcher,packets});
    if(fault)await assert.rejects(run);
    else assert.deepEqual(await run(),{status:'PASSED_SYNTHETIC_LIFECYCLE',documents:11,chunks:78,hybridQueries:11,
      withdrawals:11,embedding:MODEL,clinicalReview:'NOT_REVIEWED',qualityMetrics:null});
  }
});
