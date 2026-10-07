import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateLocalPacket,localApiBase,importPendingPacket} from '../lib/local-import.mjs';
const packet=()=>({schemaVersion:1,title:'Engineering fixture, not medical guidance',body:'Synthetic test body',
  contentSha256:createHash('sha256').update('Synthetic test body').digest('hex'),metadata:{schemaVersion:1,language:'en',contentKind:'source_extract',
    sources:[{sourceId:'fixture',publisher:'Test',url:'https://example.org/fixture'}],topics:[],population:[],exclusions:[],prerequisites:[],
    evidenceUses:['general_information'],permissionStatus:'pending',permissionEvidence:null}});
test('body tampering and undeclared evidence use fail closed',()=>{
  assert.equal(validateLocalPacket(packet()).metadata.permissionStatus,'pending');
  assert.throws(()=>validateLocalPacket({...packet(),body:'changed'}),/HASH/);
  const p=packet();p.metadata.evidenceUses=[];assert.throws(()=>validateLocalPacket(p),/EVIDENCE_USE/);
});
test('external or credential-bearing backend is rejected',()=>{
  for(const url of ['https://example.org/api/','http://user:secret@localhost:8081/api/','http://localhost:8081/api/?token=x'])
    assert.throws(()=>localApiBase(url),/LOCAL_BACKEND/);
});
test('import makes exactly one pending-create call, never an approval',async()=>{
  const calls=[];
  const result=await importPendingPacket(packet(),{token:'fixture-token',fetcher:async(url,options)=>{
    calls.push({url:String(url),options});return {ok:true,json:async()=>({id:'fixture-id',status:'PENDING_REVIEW'})};
  }});
  assert.equal(calls.length,1);assert.ok(calls[0].url.endsWith('/documents'));
  assert.equal(calls[0].options.redirect,'error');assert.equal(result.status,'PENDING_REVIEW');
  assert.equal(JSON.parse(calls[0].options.body).metadata.permissionStatus,'pending');
});
test('missing token, backend errors and unexpected READY are not successes',async()=>{
  await assert.rejects(importPendingPacket(packet(),{}),/TOKEN/);
  await assert.rejects(importPendingPacket(packet(),{token:'fixture',fetcher:async()=>({ok:false,status:403})}),/HTTP_403/);
  await assert.rejects(importPendingPacket(packet(),{token:'fixture',fetcher:async()=>({ok:true,json:async()=>({id:'x',status:'READY'})})}),/STATUS/);
});
