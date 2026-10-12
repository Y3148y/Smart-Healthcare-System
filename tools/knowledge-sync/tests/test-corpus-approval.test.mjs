import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readdirSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {approveTestDocument,readTestCorpus,TEST_EVIDENCE} from '../lib/test-corpus-approval.mjs';

const fixture=()=>({schemaVersion:1,title:'Synthetic document',body:'Synthetic text',
  contentSha256:createHash('sha256').update('Synthetic text').digest('hex'),metadata:{schemaVersion:1,
    language:'en',contentKind:'source_extract',sources:[{sourceId:'fixture',publisher:'Test',url:'https://example.org'}],
    topics:[],population:[],exclusions:[],prerequisites:[],evidenceUses:['general_information'],
    permissionStatus:'pending',permissionEvidence:null}});
function corpusPaths(dir) {
  return readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?corpusPaths(join(dir,entry.name)):
    entry.name==='document.zh-CN.md'?[join(dir,entry.name)]:[]).sort();
}
test('all eleven source-bound documents remain pending in the checked-in corpus',()=>{
  const paths=corpusPaths(resolve(fileURLToPath(new URL('../../../knowledge/corpus/',import.meta.url))));
  const packets=readTestCorpus(paths);
  assert.equal(packets.length,11);
  for(const packet of packets) assert.equal(packet.metadata.permissionStatus,'pending');
  assert.throws(()=>readTestCorpus(paths.slice(1)),/ELEVEN/);
});
test('test approval imports pending, records local-only evidence, approves and verifies chunks',async()=>{
  const calls=[];
  const fetcher=async(url,options)=>{
    const path=new URL(url).pathname;
    calls.push({path,method:options.method,body:options.body?JSON.parse(options.body):null});
    if(path.endsWith('/documents')) return {ok:true,json:async()=>({id:'doc1',status:'PENDING_REVIEW'})};
    if(path.endsWith('/details')) return {ok:true,json:async()=>({document:{status:'PENDING_REVIEW'}})};
    if(path.endsWith('/metadata') && options.method==='GET')
      return {ok:true,json:async()=>({metadata:{permissionStatus:'pending'}})};
    if(path.endsWith('/metadata')) return {ok:true,json:async()=>({status:'PENDING_REVIEW'})};
    if(path.endsWith('/approve')) return {ok:true,json:async()=>({status:'READY'})};
    if(path.endsWith('/chunks')) return {ok:true,json:async()=>([{id:'chunk'}])};
    throw new Error('UNEXPECTED_REQUEST');
  };
  const result=await approveTestDocument(fixture(),{token:'fixture',fetcher});
  assert.equal(result.chunks,1);
  assert.deepEqual(calls.map(call=>call.method),['POST','GET','GET','PUT','POST','GET']);
  assert.equal(calls[0].body.metadata.permissionStatus,'pending');
  assert.equal(calls[3].body.permissionStatus,'permitted');
  assert.equal(calls[3].body.permissionEvidence,TEST_EVIDENCE);
  assert.equal(fixture().metadata.permissionStatus,'pending');
});
test('external endpoint, missing token, bad state and existing non-test approval fail closed',async()=>{
  await assert.rejects(approveTestDocument(fixture(),{base:'https://example.org/api/',token:'fixture'}),/LOCAL_BACKEND/);
  await assert.rejects(approveTestDocument(fixture(),{}),/TOKEN/);
  const packet=fixture();packet.metadata.permissionStatus='permitted';
  await assert.rejects(approveTestDocument(packet,{token:'fixture'}),/PENDING_CORPUS/);
  const calls=[];
  const fetcher=async(url,options)=>{
    calls.push(options.method);
    const path=new URL(url).pathname;
    return {ok:true,json:async()=>path.endsWith('/documents')?{id:'doc1',status:'READY'}:
      path.endsWith('/details')?{document:{status:'READY'}}:
      {metadata:{permissionStatus:'permitted',permissionEvidence:'Other approval'}}};
  };
  await assert.rejects(approveTestDocument(fixture(),{token:'fixture',fetcher}),/NOT_TEST_ONLY/);
  assert.deepEqual(calls,['POST','GET','GET']);
});
