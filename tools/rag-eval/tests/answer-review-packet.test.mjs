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
    assessments:[{assistantMessageId:'a6',version:1,result:{answerEvidence:{traceId,adoptedReferenceIds:['E1']},riskLevel:'ROUTINE',department:'全科',confidence:70,grounded:true,
      safetyAssessment:{stopRoutineFlow:false,humanReviewRecommended:false,signals:[]}}}]};
}

test('packet binds answer to its trace, patient turn, actual references and 5-turn context',()=>{
  const packet=answerReviewPacket(fixture()),item=packet.cases.at(-1);
  assert.equal(packet.reviewStatus,'UNREVIEWED');assert.equal(item.reviewable,true);
  assert.equal(item.currentUserMessageId,'u6');assert.equal(item.context.filter(m=>m.role==='USER').length,5);
  assert.equal(item.context.some(m=>m.content.includes('message 1')),false);
  assert.deepEqual(item.retrieval.adoptedReferenceIds,['E1']);
  assert.equal(item.disposition.riskLevel,'ROUTINE');assert.equal(item.answerReview.correctness,'UNREVIEWED');
  assert.deepEqual(item.triageDecisionSnapshot,{traceId:item.traceId,riskLevel:'ROUTINE',department:'全科',confidence:70});
  assert.equal(item.dispositionReview.correctness,'UNREVIEWED');
  assert.equal(JSON.stringify(packet).includes('should-not-export'),false);
  assert.equal(JSON.stringify(packet).includes('supportReview'),false,'model reviewer outcome must not anchor human review');
  assert.equal(item.context[0].role,'USER');assert.equal(item.contextOrigin,'RECONSTRUCTED_CHARACTER_WINDOW');
});

test('recorded narration removal count reconstructs the actual smaller window',()=>{
  const source=fixture();source.messages.at(-1).provenance.turnTrace.executions=[{
    kind:'CONTEXT',name:'NARRATION',contextBudget:{historyMessagesRemoved:8}}];
  const item=answerReviewPacket(source).cases.at(-1);
  assert.equal(item.reviewable,true);assert.equal(item.contextOrigin,'RECORDED_REMOVAL_COUNT');
  assert.equal(item.context.length,3);assert.ok(item.context[0].content.includes('message 5'));
  source.messages.at(-1).provenance.turnTrace.executions[0].contextBudget.historyMessagesRemoved=1;
  assert.ok(answerReviewPacket(source).cases.at(-1).exclusionReasons.includes('CONTEXT_WINDOW_METADATA_INVALID'));
});
test('oversized current question is not clipped or incorrectly labelled reviewable',()=>{
  const source=fixture();source.messages.at(-2).content='x'.repeat(4001);
  const item=answerReviewPacket(source).cases.at(-1);
  assert.equal(item.reviewable,false);assert.deepEqual(item.context,[]);
  assert.ok(item.exclusionReasons.includes('CURRENT_CONTEXT_WINDOW_INVALID'));
});
test('reconstructed character window never starts with an orphaned assistant question',()=>{
  const source=fixture();source.messages=[{id:'u5',role:'USER',content:'x'.repeat(3995)},
    {id:'a5',role:'ASSISTANT',content:'old synthetic question'},...source.messages.slice(-2)];
  const item=answerReviewPacket(source).cases.at(-1);
  assert.equal(item.context.length,1);assert.equal(item.context[0].role,'USER');
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

test('confidence calibration requires separate disposition review bound to the same turn',()=>{
  const packet=answerReviewPacket(fixture()),item=packet.cases.at(-1);
  delete item.answerReview;
  item.dispositionReview={correctness:'CORRECT',traceId:item.traceId,reviewIdentitySha256:item.reviewIdentitySha256};
  const score=scoreAnswerReviewPacket(packet);
  assert.equal(score.confidenceCalibration.evaluated,1);assert.equal(score.answerCorrectness.evaluated,0);
  item.dispositionReview={correctness:'INCORRECT',traceId:'different-trace'};
  assert.throws(()=>scoreAnswerReviewPacket(packet),/confidence snapshot and turn trace/);
  item.dispositionReview={correctness:'UNREVIEWED',traceId:item.traceId};
  const unknown=scoreAnswerReviewPacket(packet).confidenceCalibration;
  assert.equal(unknown.evaluated,0);assert.equal(unknown.brierScore,null);
});
test('changing patient context or sources cannot borrow a review from the same answer text',()=>{
  for(const mutate of [item=>item.context[0].content='changed source',
    item=>item.retrieval.retrievedReferences[0].excerpt='changed evidence',
    item=>item.triageDecisionSnapshot.department='changed department']){
    const packet=answerReviewPacket(fixture()),item=packet.cases.at(-1);mutate(item);
    assert.throws(()=>scoreAnswerReviewPacket(packet),/context and source snapshot changed/);
  }
});
