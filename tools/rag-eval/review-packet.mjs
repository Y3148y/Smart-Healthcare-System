// Generate an UNREVIEWED packet. Do not infer relevance from title/rank/score.
import {createHash} from 'node:crypto';
export function reviewPacket(report) {
  if (!report?.corpus?.sha256 || !Array.isArray(report.corpus.approved) || !Array.isArray(report.results))
    throw new Error('Invalid retrieval report');
  const corpus=report.corpus.approved.flatMap(document=>(document.chunks ?? []).map(chunk=>({
    documentId:document.id,title:document.title,...chunk,
  })));
  if (corpus.some(c=>typeof c.text !== 'string' || !c.text.trim()))
    throw new Error('Full corpus chunk text required; candidate-only reports cannot establish exhaustive labels');
  return {schemaVersion:1,corpusSha256:report.corpus.sha256,
    reviewStatus:'UNREVIEWED',reviewer:null,clinicalReview:'NOT_REVIEWED',
    instruction:'Read the complete frozen corpus for each query. Mark every chunk relevant or not relevant before exhaustive=true. Reference answer and factual claim review require separate judgement. Whole-answer faithfulness requires claimsExhaustive=true after all factual claims have been reviewed; partial review remains unknown.',
    corpus,cases:report.results.map(result=>({id:result.id,query:result.query,
      querySha256:typeof result.query==='string' ? createHash('sha256').update(result.query).digest('hex') : null,exhaustive:false,
      relevant:[],relevanceJudgements:[],referenceAnswer:null,answerReview:null,
      selectedEvidence:result.selectedEvidence,dependency:{semantic:result.semanticStatus,rerank:result.rerankStatus}}))};
}
