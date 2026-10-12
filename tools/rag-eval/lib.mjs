import {createHash} from 'node:crypto';

const sha256 = value => createHash('sha256').update(value).digest('hex');

export function reportFileName(labelVersion, runId) {
  if (typeof labelVersion !== 'string' || !labelVersion || !/^[a-zA-Z0-9-]+$/.test(runId))
    throw new Error('Invalid report identity');
  // Encode rather than interpolate arbitrary labels into filesystem paths.
  const label = Buffer.from(labelVersion, 'utf8').toString('hex');
  if (label.length > 512) throw new Error('Evaluation label is too long');
  return `topic-${label}-${runId}.json`;
}

export function validateCases(dataset) {
  if (dataset.schemaVersion !== 1 || typeof dataset.labelVersion !== 'string' || !dataset.labelVersion
      || dataset.reviewStatus !== 'NOT_CLINICALLY_REVIEWED' || !Array.isArray(dataset.cases) || !dataset.cases.length)
    throw new Error('Invalid evaluation dataset');
  const ids = new Set();
  for (const item of dataset.cases) {
    if (typeof item.id !== 'string' || !item.id || ids.has(item.id) || typeof item.query !== 'string' || !item.query.trim()
        || !Object.hasOwn(item, 'expectedTitle') || (item.expectedTitle !== null && (typeof item.expectedTitle !== 'string' || !item.expectedTitle)))
      throw new Error('Invalid or duplicate evaluation case');
    if (item.excludedTitles !== undefined && (!Array.isArray(item.excludedTitles)
        || item.excludedTitles.some(title => typeof title !== 'string' || !title || title === item.expectedTitle)
        || new Set(item.excludedTitles).size !== item.excludedTitles.length))
      throw new Error('Invalid excluded titles');
    ids.add(item.id);
  }
  return dataset;
}

export function validateExpectedTitles(dataset, approved) {
  if (!Array.isArray(approved) || approved.length === 0)
    throw new Error('No approved corpus; import and approve the intended documents before evaluation');
  const titles = new Set(approved.map(document => document.title));
  const missing = [...new Set(dataset.cases.flatMap(item => [item.expectedTitle,...(item.excludedTitles ?? [])])
    .filter(title => title !== null && !titles.has(title)))];
  if (missing.length)
    throw new Error(`Evaluation labels do not match the approved corpus: ${missing.join(' | ')}`);
}

export function evaluateCase(item, report) {
  if (!Array.isArray(report?.retrieval?.evidence) || !Array.isArray(report.candidates)
      || report.retrieval.evidence.some(e => typeof e.title !== 'string')) throw new Error('Invalid retrieval report');
  const referencesFor = (title, hash) => hash ? (report.corpus?.approved ?? [])
    .filter(d => d.title === title).flatMap(document => (document.chunks ?? [])
      .filter(chunk => chunk.contentSha256 === hash).map(chunk => ({
        chunkId:chunk.chunkId, documentVersion:chunk.documentVersion,
        contentSha256:chunk.contentSha256, chunkingVersion:chunk.chunkingVersion,
        sectionPath:chunk.sectionPath ?? [], ordinal:chunk.ordinal ?? null,
        start:chunk.start ?? null, end:chunk.end ?? null, positionUnit:chunk.positionUnit ?? null,
      }))) : [];
  const selectedEvidence = report.retrieval.evidence.map(e => ({
    title:e.title, source:e.source, excerpt:e.excerpt, score:e.score,
    contentSha256:typeof e.excerpt === 'string' ? sha256(e.excerpt) : null,
    chunkReferences:referencesFor(e.title, typeof e.excerpt === 'string' ? sha256(e.excerpt) : null),
  }));
  // One document may contribute several selected chunks. Topic labels are document-level,
  // so count each title once and retain its first rank instead of inflating duplicates.
  const selected = [...new Set(selectedEvidence.map(e => e.title))];
  const dependencyPassed = report.semanticStatus === 'READY' && ['OK', 'EMPTY'].includes(report.rerankStatus);
  const rerankSkipped = report.rerankStatus === 'DISABLED_BY_CONFIGURATION';
  const configuredBypassReady = rerankSkipped && report.semanticStatus === 'READY'
    && report.mode === 'HYBRID_QDRANT_UNRERANKED';
  const operationalPassed = dependencyPassed || configuredBypassReady;
  const excludedSelected = selected.filter(title => (item.excludedTitles ?? []).includes(title));
  const targetMet = item.expectedTitle === null ? selected.length === 0 : selected.includes(item.expectedTitle);
  const expectationMet = targetMet && excludedSelected.length === 0;
  return {id:item.id, groupId:item.groupId ?? item.id, query:item.query, expectedTitle:item.expectedTitle, selected,
    dependencyPassed, rerankSkipped, operationalPassed, targetMet, expectationMet, excludedSelected,
    strictRerankedPass:dependencyPassed && expectationMet, passed:operationalPassed && expectationMet,
    semanticStatus:report.semanticStatus, rerankStatus:report.rerankStatus, mode:report.mode,
    selectedEvidence,
    extraSelectionsForReview:item.expectedTitle === null ? selected : selected.filter(title => title !== item.expectedTitle),
    elapsedMs:report.elapsedMs,
    candidates:report.candidates.map(c => {
      const contentSha256 = typeof c.excerpt === 'string' ? sha256(c.excerpt) : null;
      const chunkReferences = referencesFor(c.title, contentSha256);
      return {title:c.title, source:c.source, excerpt:c.excerpt, contentSha256, chunkReferences,
        lexicalRank:c.lexicalRank, lexicalScore:c.lexicalScore,
      semanticRank:c.semanticRank, semanticScore:c.semanticScore, fusedScore:c.fusedScore,
      rerankScore:c.rerankScore, kept:c.kept, reason:c.reason, lexicalTerms:c.lexicalTerms || []};
    })};
}

export function summarize(results) {
  const covered = results.filter(r => r.expectedTitle !== null);
  const durations = results.map(r => r.elapsedMs).filter(v => Number.isFinite(v) && v >= 0).sort((a,b)=>a-b);
  const percentile = p => durations.length ? durations[Math.ceil(p * durations.length) - 1] : null;
  const targetMetrics = covered.length ? {
    denominator: covered.length,
    candidateTargetHitRate: covered.filter(r => r.candidates.some(c => c.title === r.expectedTitle)).length / covered.length,
    finalTargetHitRate: covered.filter(r => r.selected.includes(r.expectedTitle)).length / covered.length,
    finalTargetMrr: covered.reduce((sum,r) => {
      const rank = r.selected.indexOf(r.expectedTitle);
      return sum + (rank < 0 ? 0 : 1 / (rank + 1));
    },0) / covered.length,
    interpretation: 'Single expected document-title target, not exhaustive relevance labels or precision.'
  } : null;
  return {cases:results.length, passed:results.filter(r=>r.passed).length,
    targetMetrics, retrievalLatencyMs:{measuredCases:durations.length,p50:percentile(0.5),p95:percentile(0.95)},
    failedIds:results.filter(r=>!r.passed).map(r=>r.id),
    dependencyFailures:results.filter(r=>!(r.operationalPassed ?? r.dependencyPassed)).length,
    rerankSkippedCases:results.filter(r=>r.rerankSkipped).length,
    strictRerankedPasses:results.filter(r=>r.strictRerankedPass ?? (r.dependencyPassed && r.expectationMet)).length,
    configuredModeTargetPasses:results.filter(r=>(r.operationalPassed ?? r.dependencyPassed) && (r.targetMet ?? r.expectationMet)).length,
    configuredModeLabelPasses:results.filter(r=>(r.operationalPassed ?? r.dependencyPassed) && r.expectationMet).length,
    coveredTopicHits:results.filter(r=>r.expectedTitle !== null && (r.targetMet ?? r.expectationMet)).length,
    coveredTopicCases:results.filter(r=>r.expectedTitle !== null).length,
    noCoverageFalseSelections:results.filter(r=>r.expectedTitle === null && r.selected.length).length,
    excludedTitleSelectionFailures:results.filter(r=>r.excludedSelected?.length).length,
    casesWithExtraSelections:results.filter(r=>r.extraSelectionsForReview.length).length,
    interpretation:'Engineering topic labels only. Extra selections require review; not clinical or answer-support validation.'};
}
