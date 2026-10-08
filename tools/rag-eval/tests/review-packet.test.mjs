import test from 'node:test';
import assert from 'node:assert/strict';
import {reviewPacket} from '../review-packet.mjs';
test('review input covers unreturned chunks without prelabelling high scores as relevant',()=>{
  const packet=reviewPacket({corpus:{sha256:'snapshot',approved:[{id:'doc',title:'topic',chunks:[
    {chunkId:'returned',text:'first'},{chunkId:'not-returned',text:'second'}]}]},
    results:[{id:'q',query:'question',selectedEvidence:[{score:1}],semanticStatus:'READY',rerankStatus:'OK'}]});
  assert.equal(packet.corpus.length,2);assert.equal(packet.cases[0].exhaustive,false);
  assert.deepEqual(packet.cases[0].relevant,[]);assert.equal(packet.cases[0].referenceAnswer,null);
  assert.equal(packet.reviewStatus,'UNREVIEWED');
});
test('old candidate-only report cannot become exhaustive review input',()=>{
  assert.throws(()=>reviewPacket({corpus:{sha256:'s',approved:[{chunks:[{chunkId:'id'}]}]},results:[]}));
});
