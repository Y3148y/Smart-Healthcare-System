// Explicit review labels, not title-hit heuristics or a model's self-rating.
const ratio = (n, d) => d ? n / d : null;
const identity = item => `${item.documentVersion}:${item.contentSha256}`;

export function retrievalQuality(retrieved, label, k) {
  if (!Number.isInteger(k) || k < 1 || !Array.isArray(retrieved)
      || label?.exhaustive !== true || !Array.isArray(label.relevant))
    throw new Error('Exhaustive relevance labels and positive integer k required');
  const validate = item => {
    if (!item || typeof item.documentVersion !== 'string' || !item.documentVersion
        || !/^[a-f0-9]{64}$/.test(item.contentSha256 ?? '')) throw new Error('Invalid versioned chunk identity');
    return identity(item);
  };
  const relevant = new Set(label.relevant.map(validate));
  const ranking = [...new Set(retrieved.map(validate))].slice(0, k);
  const hits = ranking.filter(id => relevant.has(id)).length;
  const first = ranking.findIndex(id => relevant.has(id));
  return {k, relevantCount:relevant.size, retrievedCount:ranking.length, hits,
    recallAtK:ratio(hits,relevant.size), precisionAtK:ratio(hits,ranking.length),
    reciprocalRank:relevant.size ? (first < 0 ? 0 : 1 / (first + 1)) : null,
    noCoverageFalseSelection:relevant.size === 0 && ranking.length > 0};
}

export function answerQuality(review) {
  if (!review || !['CORRECT','INCORRECT','UNREVIEWED'].includes(review.correctness)
      || !Array.isArray(review.claims)) throw new Error('Invalid answer review');
  const states = ['SUPPORTED','UNSUPPORTED','UNREVIEWED'];
  if (review.claims.some(c => !states.includes(c.support))) throw new Error('Invalid claim support');
  const supported = review.claims.filter(c => c.support === 'SUPPORTED').length;
  const unsupported = review.claims.filter(c => c.support === 'UNSUPPORTED').length;
  const unreviewed = review.claims.length - supported - unsupported;
  // No factual claims (e.g. refusal) is not a fabricated 100% faithfulness score.
  return {correct:review.correctness === 'UNREVIEWED' ? null : review.correctness === 'CORRECT',
    claimCount:review.claims.length, supported, unsupported, unreviewed,
    faithfulness:unreviewed ? null : ratio(supported,review.claims.length)};
}

export function summarizeQuality(retrieval, answers) {
  const mean = (rows,key) => {
    const values=rows.map(r=>r[key]).filter(v=>typeof v === 'number');
    return {value:ratio(values.reduce((a,b)=>a+b,0),values.length), evaluated:values.length};
  };
  const judged=answers.filter(a=>a.correct !== null);
  return {retrievalCases:retrieval.length, recallAtK:mean(retrieval,'recallAtK'),
    precisionAtK:mean(retrieval,'precisionAtK'), mrr:mean(retrieval,'reciprocalRank'),
    answerCorrectness:{value:ratio(judged.filter(a=>a.correct).length,judged.length), evaluated:judged.length},
    faithfulness:mean(answers,'faithfulness'),
    interpretation:'Reviewed engineering labels only; not clinical accuracy. Unknown and claim-free cases are not scored as perfect.'};
}
