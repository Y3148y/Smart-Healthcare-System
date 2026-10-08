// Generate an UNREVIEWED packet. Do not infer relevance from title/rank/score.
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
    instruction:'Read the complete frozen corpus for each query. Mark every chunk relevant or not relevant before exhaustive=true. Reference answer and factual claim review require separate judgement.',
    corpus,cases:report.results.map(result=>({id:result.id,query:result.query,exhaustive:false,
      relevant:[],relevanceJudgements:[],referenceAnswer:null,answerReview:null,
      selectedEvidence:result.selectedEvidence,dependency:{semantic:result.semanticStatus,rerank:result.rerankStatus}}))};
}
