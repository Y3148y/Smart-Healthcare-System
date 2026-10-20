import test from 'node:test';
import assert from 'node:assert/strict';
import {reviewProvenance} from '../review-provenance.mjs';
import {scoreAnswerReviewPacket} from '../answer-review-packet.mjs';

test('old labels have unknown origin rather than an invented human reviewer',()=>{
  assert.equal(reviewProvenance({}).reviewerType,'UNSPECIFIED');
  assert.match(reviewProvenance({}).reviewNotice,/not assumed human/);
  assert.match(reviewProvenance({reviewerType:'HUMAN'}).reviewNotice,/not authenticated/);
});
test('AI-assisted label origin remains explicit and contradictory declarations are refused',()=>{
  const declaration={reviewStatus:'AI_ASSISTED_REVIEWED',reviewerType:'AI_ASSISTED'};
  assert.match(reviewProvenance(declaration).reviewNotice,/not human gold/);
  for(const reviewerType of [undefined,'HUMAN','UNSPECIFIED','fake'])
    assert.throws(()=>reviewProvenance({...declaration,reviewerType}));
});
test('answer scoring exposes the declared AI origin without certifying clinical accuracy',()=>{
  const packet={schemaVersion:1,kind:'PERSISTED_ANSWER_REVIEW',cases:[],
    reviewStatus:'AI_ASSISTED_REVIEWED',reviewerType:'AI_ASSISTED',clinicalReview:'NOT_REVIEWED'};
  const score=scoreAnswerReviewPacket(packet);
  assert.equal(score.reviewerType,'AI_ASSISTED');assert.match(score.reviewNotice,/not human gold/);
  assert.equal(score.clinicalReview,'NOT_REVIEWED');assert.equal(score.faithfulness.value,null);
});
