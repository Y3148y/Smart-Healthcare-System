import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {answerReviewPacket,scoreAnswerReviewPacket} from '../answer-review-packet.mjs';

const hash=text=>createHash('sha256').update(text,'utf8').digest('hex');
const uuid=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
function fixture() {
  const messages=[];
  for(let i=1;i<=6;i++) {
    messages.push({id:`u${i}`,role:'USER',content:`synthetic patient message ${i}`});
    if(i<6)messages.push({id:`a${i}`,role:'ASSISTANT',content:`synthetic assistant question ${i}`});
  }
  const traceId=uuid(6),answer='Synthetic answer fixture, not medical advice.';
  messages.push({id:'a6',role:'ASSISTANT',content:answer,provenance:{
    turnTrace:{traceId,userMessageId:'u6',route:'GUIDANCE',elapsedMs:45},
    answerEvidence:{traceId,retrievalStatus:'HYBRID_QDRANT',validationStatus:'REFERENCE_INTEGRITY_PASSED',
      retrievedReferences:[{id:'E1',title:'Synthetic source',source:'https://example.invalid',excerpt:'Synthetic evidence.',
        contentHash:hash('Synthetic evidence.'),applicability:'UNKNOWN',sectionPath:['Fixture']}],
      adoptedReferenceIds:['E1'],uncovered:[],supportReview:{status:'PASSED'},
      serviceNotice:{messages:[],bookingAllowed:false}}}});
  return {session:{id:'should-not-export',patient:'identifier-not-to-export'},messages,
    assessments:[{assistantMessageId:'a6',version:1,result:{answerEvidence:{traceId,adoptedReferenceIds:['E1']},riskLevel:'ROUTINE',grounded:true,
      safetyAssessment:{stopRoutineFlow:false,humanReviewRecommended:false,signals:[]}}}]};
}

test('packet binds answer to its trace, patient turn, actual references and 5-turn context',()=>{
  const packet=answerReviewPacket(fixture()),item=packet.cases.at(-1);
  assert.equal(packet.reviewStatus,'UNREVIEWED');assert.equal(item.reviewable,true);
  assert.equal(item.currentUserMessageId,'u6');assert.equal(item.context.filter(m=>m.role==='USER').length,5);
  assert.equal(item.context.some(m=>m.content.includes('message 1')),false);
  assert.deepEqual(item.retrieval.adoptedReferenceIds,['E1']);
  assert.equal(item.disposition.riskLevel,'ROUTINE');assert.equal(item.answerReview.correctness,'UNREVIEWED');
  assert.equal(JSON.stringify(packet).includes('should-not-export'),false);
  assert.equal(JSON.stringify(packet).includes('supportReview'),false,'model reviewer outcome must not anchor human review');
});

test('missing or drifting trace/reference evidence stays unreviewable',()=>{
  const source=fixture();source.messages.at(-1).provenance.turnTrace.traceId=uuid(999);
  const packet=answerReviewPacket(source),item=packet.cases.at(-1);
  assert.equal(item.reviewable,false);assert.ok(item.exclusionReasons.includes('TRACE_MISMATCH'));
  assert.equal(item.answerReview,null);
  const bad=fixture();bad.messages.at(-1).provenance.answerEvidence.retrievedReferences[0].contentHash='0'.repeat(64);
  assert.ok(answerReviewPacket(bad).cases.at(-1).exclusionReasons.includes('REFERENCE_IDENTITY_INVALID'));
  const stale=fixture();stale.assessments[0].result.answerEvidence.traceId=uuid(999);
  assert.ok(answerReviewPacket(stale).cases.at(-1).exclusionReasons.includes('ASSESSMENT_EVIDENCE_MISMATCH'));
  const wrongTurn=fixture();wrongTurn.messages.splice(-1,0,{id:'u7',role:'USER',content:'synthetic later user'});
  assert.ok(answerReviewPacket(wrongTurn).cases.at(-1).exclusionReasons.includes('CURRENT_USER_TURN_MISSING'));
});

test('scoring requires exact answer identity and adopted-source binding; partial review stays unknown',()=>{
  const packet=answerReviewPacket(fixture()),item=packet.cases.at(-1);
  item.answerReview={...item.answerReview,correctness:'CORRECT',claimsExhaustive:true,
    claims:[{text:'Synthetic factual claim.',support:'SUPPORTED',referenceIds:['E1']}]};
  const score=scoreAnswerReviewPacket(packet);
  assert.equal(score.answerCorrectness.value,1);assert.equal(score.faithfulness.value,1);
  item.answerReview={...item.answerReview,claimsExhaustive:false};
  assert.equal(scoreAnswerReviewPacket(packet).faithfulness.value,null);
  item.answerReview={...item.answerReview,correctness:'UNREVIEWED',claimsExhaustive:true};
  const partial=scoreAnswerReviewPacket(packet);
  assert.equal(partial.answerCorrectness.value,null);assert.equal(partial.faithfulness.value,1);
  item.answerReview={...item.answerReview,correctness:'CORRECT'};
  item.answerReview={...item.answerReview,claimsExhaustive:true,claims:[{text:'Claim',support:'SUPPORTED',referenceIds:['E9']}]};
  assert.throws(()=>scoreAnswerReviewPacket(packet),/actually adopted/);
  item.answerReview={...item.answerReview,claims:[{text:'Claim',support:'SUPPORTED',referenceIds:['E1']}],traceId:uuid(999)};
  assert.throws(()=>scoreAnswerReviewPacket(packet),/exact persisted message/);
});
