import {test} from 'node:test';
import assert from 'node:assert/strict';
import {eventQuality,summarizeEvents} from '../event-quality.mjs';
const messages=[{id:'u1',role:'USER',content:'synthetic symptom'},{id:'a1',role:'ASSISTANT',content:'assistant assertion'}];
const event=()=>({symptom:'symptom',bodySite:null,onset:null,duration:null,trigger:null,severity:null,
  subject:'SELF',temporality:'CURRENT',assertion:'AFFIRMED',sourceRefs:[{messageId:'u1',quote:'synthetic symptom'}]});
test('exact event labels and source-aligned attribution are scored separately',()=>{
  const good=event(),wrong={...event(),assertion:'NEGATED'};
  const result=eventQuality(messages,[wrong],{exhaustive:true,events:[good]});
  assert.equal(result.f1,0);assert.equal(result.matchedAnchors,1);
  assert.equal(result.attribution.subject.value,1);assert.equal(result.attribution.assertion.value,0);
  assert.equal(summarizeEvents([result]).exactEventRecall,0);
});
test('assistant, invented quotes or invented text fields cannot support a reviewed event',()=>{
  for(const mutation of [e=>e.sourceRefs=[{messageId:'a1',quote:'assistant assertion'}],
    e=>e.sourceRefs=[{messageId:'u1',quote:'invented'}],e=>e.bodySite='invented']){
    const invalid=event();mutation(invalid);
    assert.throws(()=>eventQuality(messages,[invalid],{exhaustive:true,events:[event()]}));
  }
});
test('unmatched events are not assigned perfect subject or temporal accuracy',()=>{
  const result=eventQuality(messages,[],{exhaustive:true,events:[event()]});
  assert.equal(result.recall,0);assert.equal(result.attribution.subject.value,null);
  assert.equal(summarizeEvents([]).exactEventF1,null);
});
test('partial labels and ambiguous duplicate anchors do not get a fabricated score',()=>{
  assert.throws(()=>eventQuality(messages,[event()],{exhaustive:false,events:[event()]}));
  assert.throws(()=>eventQuality(messages,[event(),event()],{exhaustive:true,events:[event()]}));
});
