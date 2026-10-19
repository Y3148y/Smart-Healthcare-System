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

export function summarizeRetrievalStages(cases) {
  const mean = (rows, key) => {
    const values = rows.map(row => row[key]).filter(value => typeof value === 'number');
    return {value:ratio(values.reduce((sum, value) => sum + value, 0), values.length), evaluated:values.length};
  };
  const stages = ['bm25', 'qdrant', 'rrf', 'final'];
  return Object.fromEntries(stages.map(stage => {
    const rows = cases.map(item => item.stages?.[stage]).filter(Boolean);
    return [stage, {recallAtK:mean(rows,'recallAtK'), precisionAtK:mean(rows,'precisionAtK'),
      mrr:mean(rows,'reciprocalRank'), noCoverageFalseSelections:rows.filter(row => row.noCoverageFalseSelection).length,
      evaluated:rows.length}];
  }));
}

/**
 * Compares the displayed triage confidence percentage to independent binary
 * judgements of the disposition. It is a calibration diagnostic, not accuracy
 * unless reviewers assess a representative held-out set.
 */
export function confidenceCalibration(cases, bins = 5) {
  if (!Number.isInteger(bins) || bins < 2 || bins > 20) throw new Error('Invalid calibration bin count');
  const judged = cases.filter(item => Number.isFinite(item.confidence) && item.confidence >= 0 && item.confidence <= 100
      && (item.correctness === 'CORRECT' || item.correctness === 'INCORRECT'));
  const groups = Array.from({length:bins}, (_, index) => ({index, count:0, confidenceSum:0, correct:0}));
  for (const item of judged) {
    const index = Math.min(bins - 1, Math.floor(item.confidence / 100 * bins));
    const group = groups[index]; group.count++; group.confidenceSum += item.confidence / 100;
    if (item.correctness === 'CORRECT') group.correct++;
  }
  const populated = groups.filter(group => group.count > 0).map(group => ({
    range: `${Math.round(group.index * 100 / bins)}-${Math.round((group.index + 1) * 100 / bins)}%`,
    count:group.count, meanConfidence:group.confidenceSum / group.count,
    observedAccuracy:group.correct / group.count,
  }));
  const brier = judged.length ? judged.reduce((sum,item) => {
    const probability = item.confidence / 100, outcome = item.correctness === 'CORRECT' ? 1 : 0;
    return sum + (probability - outcome) ** 2;
  }, 0) / judged.length : null;
  const ece = judged.length ? groups.reduce((sum,group) => group.count
    ? sum + group.count / judged.length * Math.abs(group.confidenceSum / group.count - group.correct / group.count) : sum, 0) : null;
  return {evaluated:judged.length, brierScore:brier, expectedCalibrationError:ece, bins:populated,
    interpretation:'Requires independent disposition labels on a representative held-out set; current system scores are heuristic/model-provided and are not validated probabilities.'};
}
