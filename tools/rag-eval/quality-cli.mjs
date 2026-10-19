// Offline scoring; no network calls, credentials or automatic medical labels.
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {retrievalQuality,answerQuality,summarizeQuality,summarizeRetrievalStages} from './quality.mjs';

try {
  const [reportPath,labelPath,kArgument='3']=process.argv.slice(2);
  if (!reportPath || !labelPath) throw new Error('Usage: node quality-cli.mjs report.json reviewed-labels.json [k]');
  const report=JSON.parse(readFileSync(reportPath,'utf8'));
  const labels=JSON.parse(readFileSync(labelPath,'utf8'));
  if (!Array.isArray(report.results) || labels.schemaVersion !== 1 || !Array.isArray(labels.cases)
      || !labels.corpusSha256 || labels.corpusSha256 !== report.corpus?.sha256)
    throw new Error('Labels must match the exact report corpus snapshot');
  const approved=report.corpus.approved;
  if (!Array.isArray(approved)
      || createHash('sha256').update(JSON.stringify(approved)).digest('hex') !== report.corpus.sha256)
    throw new Error('Complete unchanged corpus snapshot required');
  const identity=chunk=>`${chunk.documentVersion}:${chunk.contentSha256}`;
  const corpusIds=new Set();
  for (const document of approved) {
    if (!Array.isArray(document.chunks)) throw new Error('Complete corpus chunks required');
    for (const chunk of document.chunks) {
      if (typeof chunk.text !== 'string' || !chunk.text.trim()
          || createHash('sha256').update(chunk.text).digest('hex') !== chunk.contentSha256)
        throw new Error('Corpus chunk content does not match its fingerprint');
      corpusIds.add(identity(chunk));
    }
  }
  const resultsById=new Map();
  for (const result of report.results) {
    if (typeof result.id !== 'string' || !result.id.trim() || resultsById.has(result.id))
      throw new Error('Invalid or duplicate report case id');
    if (typeof result.query !== 'string' || !result.query.trim())
      throw new Error('Report cases must include the exact query');
    resultsById.set(result.id,result);
  }
  const byId=new Map();
  for(const label of labels.cases) {
    if (typeof label.id !== 'string' || byId.has(label.id)) throw new Error('Invalid or duplicate label id');
    const result=resultsById.get(label.id);
    if (!result) throw new Error('Label id not present in report');
    const hasQuery=Object.hasOwn(label,'query'),hasQueryHash=Object.hasOwn(label,'querySha256');
    const queryHash=createHash('sha256').update(result.query).digest('hex');
    if ((!hasQuery && !hasQueryHash) || hasQuery && label.query !== result.query
        || hasQueryHash && label.querySha256 !== queryHash)
      throw new Error('Relevance labels must match the exact case query');
    if (!Array.isArray(label.relevant) || label.relevant.some(chunk=>!corpusIds.has(identity(chunk))))
      throw new Error('Relevance label references a chunk outside the frozen corpus');
    byId.set(label.id,label);
  }
  const retrieval=[],answers=[],cases=[],stageCases=[];
  let unlabelled=0,unreviewedAnswers=0;
  for(const result of report.results) {
    const label=byId.get(result.id);
    if (!label) {unlabelled++;cases.push({id:result.id,status:'UNLABELLED'});continue;}
    const ranking=(result.selectedEvidence ?? []).map(e=>{
      if (!Array.isArray(e.chunkReferences) || e.chunkReferences.length !== 1)
        throw new Error('Selected evidence identity is missing or ambiguous');
      if (!corpusIds.has(identity(e.chunkReferences[0])))
        throw new Error('Selected evidence references a chunk outside the frozen corpus');
      if (typeof e.excerpt !== 'string'
          || createHash('sha256').update(e.excerpt).digest('hex') !== e.chunkReferences[0].contentSha256)
        throw new Error('Selected excerpt does not match its chunk fingerprint');
      return e.chunkReferences[0];
    });
    const quality=retrievalQuality(ranking,label,Number(kArgument));
    retrieval.push(quality);
    const stageCandidates=(result.candidates ?? []).map(candidate=>{
      if(!Array.isArray(candidate.chunkReferences)||candidate.chunkReferences.length!==1)
        throw new Error('Candidate chunk identity is missing or ambiguous');
      const chunk=candidate.chunkReferences[0];
      if(!corpusIds.has(identity(chunk))||typeof candidate.excerpt!=='string'
          ||createHash('sha256').update(candidate.excerpt).digest('hex')!==chunk.contentSha256)
        throw new Error('Candidate excerpt does not match a chunk in the frozen corpus');
      return {...candidate,chunk};
    });
    const ordered={
      bm25:stageCandidates.filter(c=>Number.isInteger(c.lexicalRank)&&c.lexicalRank>0)
        .sort((a,b)=>a.lexicalRank-b.lexicalRank).map(c=>c.chunk),
      qdrant:stageCandidates.filter(c=>Number.isInteger(c.semanticRank)&&c.semanticRank>0)
        .sort((a,b)=>a.semanticRank-b.semanticRank).map(c=>c.chunk),
      rrf:stageCandidates.filter(c=>Number.isFinite(c.fusedScore))
        .sort((a,b)=>b.fusedScore-a.fusedScore).map(c=>c.chunk),
      final:ranking,
    };
    const stages=Object.fromEntries(Object.entries(ordered).map(([name,items])=>[name,retrievalQuality(items,label,Number(kArgument))]));
    stageCases.push({id:result.id,stages});
    const relevant=new Set(label.relevant.map(identity));
    const selected=[...new Map(ranking.map(chunk=>[identity(chunk),chunk])).values()].slice(0,Number(kArgument));
    const selectedIds=new Set(selected.map(identity));
    cases.push({id:result.id,status:'LABELLED',retrieval:quality,retrievalStages:stages,
      missed:label.relevant.filter(chunk=>!selectedIds.has(identity(chunk))),
      extra:selected.filter(chunk=>!relevant.has(identity(chunk)))});
    if (label.answerReview) {
      const snapshot=result.answerSnapshot,review=label.answerReview;
      if (!snapshot || typeof snapshot.text !== 'string' || !snapshot.text.trim()
          || typeof snapshot.messageId !== 'string' || !snapshot.messageId
          || typeof snapshot.traceId !== 'string' || !snapshot.traceId
          || createHash('sha256').update(snapshot.text).digest('hex') !== snapshot.contentSha256
          || review.answerSha256 !== snapshot.contentSha256
          || review.answerMessageId !== snapshot.messageId || review.traceId !== snapshot.traceId)
        throw new Error('Answer review must match the exact versioned answer snapshot');
      answers.push(answerQuality(review));
    }
    else unreviewedAnswers++;
  }
  console.log(JSON.stringify({labelVersion:labels.labelVersion ?? null,reviewer:labels.reviewer ?? null,
    reviewStatus:labels.reviewStatus ?? 'UNSPECIFIED',clinicalReview:labels.clinicalReview ?? 'UNSPECIFIED',
    split:labels.split ?? 'UNSPECIFIED',
    reportCases:report.results.length,unlabelled,unreviewedAnswers,
    summary:{...summarizeQuality(retrieval,answers),retrievalStages:summarizeRetrievalStages(stageCases)},cases},null,2));
} catch(error) {
  // Do not print label bodies or patient text on parse errors.
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.message);
  process.exitCode=1;
}
