export function validateCases(dataset) {
  if (dataset.schemaVersion !== 1 || typeof dataset.labelVersion !== 'string' || !dataset.labelVersion
      || dataset.reviewStatus !== 'NOT_CLINICALLY_REVIEWED' || !Array.isArray(dataset.cases) || !dataset.cases.length)
    throw new Error('Invalid evaluation dataset');
  const ids = new Set();
  for (const item of dataset.cases) {
    if (typeof item.id !== 'string' || !item.id || ids.has(item.id) || typeof item.query !== 'string' || !item.query.trim()
        || !Object.hasOwn(item, 'expectedTitle') || (item.expectedTitle !== null && (typeof item.expectedTitle !== 'string' || !item.expectedTitle)))
      throw new Error('Invalid or duplicate evaluation case');
    ids.add(item.id);
  }
  return dataset;
}

export function evaluateCase(item, report) {
  if (!Array.isArray(report?.retrieval?.evidence) || !Array.isArray(report.candidates)
      || report.retrieval.evidence.some(e => typeof e.title !== 'string')) throw new Error('Invalid retrieval report');
  const selected = report.retrieval.evidence.map(e => e.title);
  const dependencyPassed = report.semanticStatus === 'READY' && ['OK', 'EMPTY'].includes(report.rerankStatus);
  const expectationMet = item.expectedTitle === null ? selected.length === 0 : selected.includes(item.expectedTitle);
  return {id:item.id, query:item.query, expectedTitle:item.expectedTitle, selected,
    dependencyPassed, expectationMet, passed:dependencyPassed && expectationMet,
    semanticStatus:report.semanticStatus, rerankStatus:report.rerankStatus, mode:report.mode,
    extraSelectionsForReview:item.expectedTitle === null ? selected : selected.filter(title => title !== item.expectedTitle),
    elapsedMs:report.elapsedMs,
    candidates:report.candidates.map(c => ({title:c.title, lexicalRank:c.lexicalRank, lexicalScore:c.lexicalScore,
      semanticRank:c.semanticRank, semanticScore:c.semanticScore, fusedScore:c.fusedScore,
      rerankScore:c.rerankScore, kept:c.kept, reason:c.reason, lexicalTerms:c.lexicalTerms || []}))};
}

export function summarize(results) {
  return {cases:results.length, passed:results.filter(r=>r.passed).length,
    failedIds:results.filter(r=>!r.passed).map(r=>r.id),
    dependencyFailures:results.filter(r=>!r.dependencyPassed).length,
    coveredTopicHits:results.filter(r=>r.expectedTitle !== null && r.expectationMet).length,
    coveredTopicCases:results.filter(r=>r.expectedTitle !== null).length,
    noCoverageFalseSelections:results.filter(r=>r.expectedTitle === null && r.selected.length).length,
    casesWithExtraSelections:results.filter(r=>r.extraSelectionsForReview.length).length,
    interpretation:'Engineering topic labels only. Extra selections require review; not clinical or answer-support validation.'};
}
