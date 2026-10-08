// Offline scoring; no network calls, credentials or automatic medical labels.
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {retrievalQuality,answerQuality,summarizeQuality} from './quality.mjs';

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
  const byId=new Map();
  for(const label of labels.cases) {
    if (typeof label.id !== 'string' || byId.has(label.id)) throw new Error('Invalid or duplicate label id');
    if (!Array.isArray(label.relevant) || label.relevant.some(chunk=>!corpusIds.has(identity(chunk))))
      throw new Error('Relevance label references a chunk outside the frozen corpus');
    byId.set(label.id,label);
  }
  const retrieval=[],answers=[],cases=[];
  let unlabelled=0,unreviewedAnswers=0;
  const known=new Set(report.results.map(r=>r.id));
  if (known.size !== report.results.length) throw new Error('Duplicate report case id');
  if (labels.cases.some(l=>!known.has(l.id))) throw new Error('Label id not present in report');
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
    const relevant=new Set(label.relevant.map(identity));
    const selected=[...new Map(ranking.map(chunk=>[identity(chunk),chunk])).values()].slice(0,Number(kArgument));
    const selectedIds=new Set(selected.map(identity));
    cases.push({id:result.id,status:'LABELLED',retrieval:quality,
      missed:label.relevant.filter(chunk=>!selectedIds.has(identity(chunk))),
      extra:selected.filter(chunk=>!relevant.has(identity(chunk)))});
    if (label.answerReview) answers.push(answerQuality(label.answerReview));
    else unreviewedAnswers++;
  }
  console.log(JSON.stringify({labelVersion:labels.labelVersion ?? null,reviewer:labels.reviewer ?? null,
    reviewStatus:labels.reviewStatus ?? 'UNSPECIFIED',clinicalReview:labels.clinicalReview ?? 'UNSPECIFIED',
    split:labels.split ?? 'UNSPECIFIED',
    reportCases:report.results.length,unlabelled,unreviewedAnswers,
    summary:summarizeQuality(retrieval,answers),cases},null,2));
} catch(error) {
  // Do not print label bodies or patient text on parse errors.
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.message);
  process.exitCode=1;
}
