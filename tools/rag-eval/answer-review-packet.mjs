import {createHash} from 'node:crypto';
import {confidenceCalibration} from './quality.mjs';

const sha256=text=>createHash('sha256').update(text,'utf8').digest('hex');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ROLES=new Set(['USER','ASSISTANT']);
const reviewIdentity=item=>sha256(JSON.stringify({messageId:item.messageId,traceId:item.traceId,
  answerSnapshot:item.answerSnapshot,currentUserMessageId:item.currentUserMessageId,
  context:item.context,contextOrigin:item.contextOrigin,retrieval:item.retrieval,
  disposition:item.disposition,triageDecisionSnapshot:item.triageDecisionSnapshot,serviceNotice:item.serviceNotice}));

function boundedHistory(messages, assistantIndex, turn) {
  const source=messages.slice(0,assistantIndex).filter(m=>m&&ROLES.has(m.role)
      && typeof m.content==='string' && m.content.trim())
    .map(m=>({role:m.role,content:m.content}));
  if(!source.length||source.at(-1).role!=='USER'||source.at(-1).content.length>4000)
    return {messages:[],origin:'UNAVAILABLE',issue:'CURRENT_CONTEXT_WINDOW_INVALID'};
  const recorded=(turn?.executions??[]).filter(event=>event.kind==='CONTEXT'&&event.name==='NARRATION'&&event.contextBudget);
  if(recorded.length){
    const removed=recorded[0].contextBudget.historyMessagesRemoved;
    if(recorded.length!==1||!Number.isSafeInteger(removed)||removed<0||removed>=source.length)
      return {messages:[],origin:'UNAVAILABLE',issue:'CONTEXT_WINDOW_METADATA_INVALID'};
    const selected=source.slice(removed);
    if(selected[0].role!=='USER'||selected.filter(m=>m.role==='USER').length>5
        ||selected.reduce((n,m)=>n+m.content.length,0)>4000)
      return {messages:[],origin:'UNAVAILABLE',issue:'CONTEXT_WINDOW_METADATA_INVALID'};
    return {messages:selected,origin:'RECORDED_REMOVAL_COUNT',issue:null};
  }
  const window=[...source];
  let characters=window.reduce((n,m)=>n+m.content.length,0);
  let userTurns=window.filter(m=>m.role==='USER').length;
  while(window.length>1&&(characters>4000||userTurns>5)) {
    const removed=window.shift();characters-=removed.content.length;
    if(removed.role==='USER')userTurns--;
  }
  while(window.length&&window[0].role!=='USER')window.shift();
  return {messages:window,origin:'RECONSTRUCTED_CHARACTER_WINDOW',issue:null};
}

function answerCase(message,index,messages,assessments) {
  const diagnostics=message.provenance?.answerEvidence;
  const turn=message.provenance?.turnTrace;
  const history=boundedHistory(messages,index,turn);
  const userMessageId=turn?.userMessageId;
  const precedingUsers=messages.slice(0,index).filter(m=>m.role==='USER');
  const currentUser=precedingUsers.at(-1)?.id===userMessageId;
  const reasons=[];
  if(history.issue)reasons.push(history.issue);
  if(!diagnostics||!turn) reasons.push('MISSING_TURN_PROVENANCE');
  if(!UUID.test(diagnostics?.traceId??'')||diagnostics?.traceId!==turn?.traceId) reasons.push('TRACE_MISMATCH');
  if(typeof userMessageId!=='string'||!userMessageId||!currentUser) reasons.push('CURRENT_USER_TURN_MISSING');
  if(typeof message.content!=='string'||!message.content.trim()) reasons.push('ANSWER_EMPTY');
  const references=Array.isArray(diagnostics?.retrievedReferences)?diagnostics.retrievedReferences:[];
  const referenceIds=new Set();
  for(const reference of references) {
    if(!reference||typeof reference.id!=='string'||!reference.id||referenceIds.has(reference.id)
        ||typeof reference.excerpt!=='string'||!reference.excerpt.trim()
        ||sha256(reference.excerpt)!==reference.contentHash) reasons.push('REFERENCE_IDENTITY_INVALID');
    if(reference?.id)referenceIds.add(reference.id);
  }
  const adopted=Array.isArray(diagnostics?.adoptedReferenceIds)?diagnostics.adoptedReferenceIds:[];
  if(new Set(adopted).size!==adopted.length||adopted.some(id=>!referenceIds.has(id))) reasons.push('ADOPTED_REFERENCE_INVALID');
  const assessment=assessments.get(message.id)??null;
  if(assessment) {
    const assessmentEvidence=assessment.result?.answerEvidence;
    if(!assessmentEvidence||assessmentEvidence.traceId!==diagnostics?.traceId
        ||JSON.stringify(assessmentEvidence.adoptedReferenceIds??[])!==JSON.stringify(diagnostics?.adoptedReferenceIds??[]))
      reasons.push('ASSESSMENT_EVIDENCE_MISMATCH');
  }
  const safety=assessment?.result?.safetyAssessment??null;
  const decision=assessment?.result;
  const decisionConfidence=Number.isFinite(decision?.confidence)?decision.confidence:null;
  const result={messageId:message.id,traceId:diagnostics?.traceId??null,
    answerSnapshot:{text:message.content,contentSha256:typeof message.content==='string'?sha256(message.content):null},
    currentUserMessageId:userMessageId??null,context:history.messages,contextOrigin:history.origin,
    disposition:safety?{riskLevel:assessment.result.riskLevel??null,grounded:assessment.result.grounded??null,
      stopRoutineFlow:safety.stopRoutineFlow??null,humanReviewRecommended:safety.humanReviewRecommended??null,
      signals:(safety.signals??[]).map(s=>({ruleCode:s.ruleCode,category:s.category,evidence:s.evidence,reason:s.reason}))}:null,
    retrieval:{status:diagnostics?.retrievalStatus??null,validationStatus:diagnostics?.validationStatus??null,
      retrievedReferences:references.map(r=>({id:r.id,title:r.title,source:r.source,excerpt:r.excerpt,
        contentHash:r.contentHash,applicability:r.applicability,sectionPath:r.sectionPath??[]})),
      adoptedReferenceIds:adopted,uncovered:diagnostics?.uncovered??[]},
    triageDecisionSnapshot:decision?{traceId:diagnostics?.traceId??null,riskLevel:decision.riskLevel??null,
      department:decision.department??null,confidence:decisionConfidence}:null,
    dispositionReview:decision&&decisionConfidence!==null
      ?{correctness:'UNREVIEWED',traceId:diagnostics?.traceId??null}:null,
    serviceNotice:diagnostics?.serviceNotice??null,
    reviewable:reasons.length===0,exclusionReasons:[...new Set(reasons)],answerReview:null};
  result.reviewIdentitySha256=reviewIdentity(result);
  if(result.dispositionReview)result.dispositionReview.reviewIdentitySha256=result.reviewIdentitySha256;
  if(result.reviewable) result.answerReview={correctness:'UNREVIEWED',claimsExhaustive:false,claims:[],
    answerSha256:result.answerSnapshot.contentSha256,answerMessageId:result.messageId,traceId:result.traceId,
    reviewIdentitySha256:result.reviewIdentitySha256};
  return result;
}

/** Convert one locally exported persisted conversation; no HTTP, credentials or automatic labels. */
export function answerReviewPacket(conversation) {
  if(!conversation||!Array.isArray(conversation.messages)||!Array.isArray(conversation.assessments))
    throw new Error('A persisted conversation with messages and assessments is required');
  const assessments=new Map();
  for(const assessment of conversation.assessments) {
    const id=assessment?.assistantMessageId;
    if(typeof id==='string'&&id) {
      if(assessments.has(id)) throw new Error('Ambiguous assessment-to-answer association');
      assessments.set(id,assessment);
    }
  }
  const cases=[];
  conversation.messages.forEach((message,index)=>{
    if(message?.role==='ASSISTANT') cases.push(answerCase(message,index,conversation.messages,assessments));
  });
  return {schemaVersion:1,kind:'PERSISTED_ANSWER_REVIEW',reviewStatus:'UNREVIEWED',reviewer:null,
    clinicalReview:'NOT_REVIEWED',instruction:'Review answer correctness against the full turn context and the supplied disposition. Separately judge whether the triage risk/department disposition is correct in dispositionReview; do not infer disposition correctness from fluent wording. Judge support only against references actually adopted; retrieved-but-not-adopted references are context, not evidence used by the answer. Enumerate every factual claim before setting claimsExhaustive=true. This is an engineering review packet, not clinical approval.',
    cases};
}

export function scoreAnswerReviewPacket(packet) {
  if(packet?.schemaVersion!==1||packet.kind!=='PERSISTED_ANSWER_REVIEW'||!Array.isArray(packet.cases))
    throw new Error('Invalid persisted-answer review packet');
  const seen=new Set(),scored=[],rows=[],dispositionCases=[];let unreviewed=0,unreviewable=0;
  for(const item of packet.cases) {
    if(typeof item.messageId!=='string'||!item.messageId||seen.has(item.messageId)) throw new Error('Invalid or duplicate answer message identity');
    seen.add(item.messageId);
    if(!item.reviewable){unreviewable++;rows.push({messageId:item.messageId,status:'UNREVIEWABLE'});continue;}
    if(item.reviewIdentitySha256!==reviewIdentity(item))
      throw new Error('Review context and source snapshot changed; regenerate the review packet');
    const snapshot=item.answerSnapshot,review=item.answerReview;
    if(!review){unreviewed++;rows.push({messageId:item.messageId,status:'UNREVIEWED'});}
    else {
      if(!snapshot||typeof snapshot.text!=='string'||sha256(snapshot.text)!==snapshot.contentSha256
          ||review.answerSha256!==snapshot.contentSha256||review.answerMessageId!==item.messageId
          ||review.traceId!==item.traceId||review.reviewIdentitySha256!==item.reviewIdentitySha256)
        throw new Error('Answer review must match the exact persisted message, text hash and trace, and review identity');
      const adopted=new Set(item.retrieval?.adoptedReferenceIds??[]);
      if(review.claims.some(claim=>claim.support==='SUPPORTED'
          &&(!Array.isArray(claim.referenceIds)||!claim.referenceIds.length||claim.referenceIds.some(id=>!adopted.has(id)))))
        throw new Error('Supported claims must reference material actually adopted for this answer');
      const quality=answerQuality(review);
      if(quality.correct===null)unreviewed++;
      if(quality.correct!==null||typeof quality.faithfulness==='number')scored.push(quality);
      rows.push({messageId:item.messageId,status:review.correctness==='UNREVIEWED'?'UNREVIEWED':'REVIEWED',quality});
    }
    const decision=item.triageDecisionSnapshot,decisionReview=item.dispositionReview;
    if(decisionReview&&decisionReview.correctness!=='UNREVIEWED') {
      if(!decision||decisionReview.traceId!==item.traceId||decision.traceId!==item.traceId
          ||decisionReview.reviewIdentitySha256!==item.reviewIdentitySha256
          ||!Number.isFinite(decision.confidence)||decision.confidence<0||decision.confidence>100
          ||!['CORRECT','INCORRECT'].includes(decisionReview.correctness))
        throw new Error('Disposition review must match a valid confidence snapshot and turn trace');
      dispositionCases.push({confidence:decision.confidence,correctness:decisionReview.correctness});
    }
  }
  const correctness=scored.filter(q=>q.correct!==null);
  const faithfulness=scored.filter(q=>typeof q.faithfulness==='number');
  const ratio=(n,d)=>d?n/d:null;
  return {reviewStatus:packet.reviewStatus??'UNSPECIFIED',reviewer:packet.reviewer??null,
    clinicalReview:packet.clinicalReview??'UNSPECIFIED',reviewedAnswers:correctness.length,unreviewed,unreviewable,
    answerCorrectness:{value:ratio(correctness.filter(q=>q.correct===true).length,correctness.length),evaluated:correctness.length},
    faithfulness:{value:ratio(faithfulness.reduce((n,q)=>n+q.faithfulness,0),faithfulness.length),
      evaluated:faithfulness.length},confidenceCalibration:confidenceCalibration(dispositionCases),cases:rows,
    interpretation:'Reviewer-supplied labels only; reviewer identity and clinical competence are not authenticated. Confidence calibration requires representative independent disposition labels and is not a clinical accuracy claim.'};
}

function answerQuality(review) {
  if(!review||!['CORRECT','INCORRECT','UNREVIEWED'].includes(review.correctness)
      ||!Array.isArray(review.claims)) throw new Error('Invalid answer review');
  const allowed=new Set(['SUPPORTED','UNSUPPORTED','UNREVIEWED']);
  if(review.claims.some(c=>!c||!allowed.has(c.support)||typeof c.text!=='string'||!c.text.trim()))
    throw new Error('Every factual claim needs text and a support judgement');
  const supported=review.claims.filter(c=>c.support==='SUPPORTED').length;
  const unsupported=review.claims.filter(c=>c.support==='UNSUPPORTED').length;
  const unreviewed=review.claims.length-supported-unsupported;
  return {correct:review.correctness==='UNREVIEWED'?null:review.correctness==='CORRECT',claimCount:review.claims.length,
    supported,unsupported,unreviewed,claimsExhaustive:review.claimsExhaustive===true,
    faithfulness:review.claimsExhaustive!==true||unreviewed||review.claims.length===0?null:supported/review.claims.length};
}
