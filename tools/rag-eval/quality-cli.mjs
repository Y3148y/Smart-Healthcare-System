// Offline scoring; no network calls, credentials or automatic medical labels.
import {readFileSync} from 'node:fs';
import {retrievalQuality,answerQuality,summarizeQuality} from './quality.mjs';

try {
  const [reportPath,labelPath,kArgument='3']=process.argv.slice(2);
  if (!reportPath || !labelPath) throw new Error('Usage: node quality-cli.mjs report.json reviewed-labels.json [k]');
  const report=JSON.parse(readFileSync(reportPath,'utf8'));
  const labels=JSON.parse(readFileSync(labelPath,'utf8'));
  if (!Array.isArray(report.results) || labels.schemaVersion !== 1 || !Array.isArray(labels.cases)
      || !labels.corpusSha256 || labels.corpusSha256 !== report.corpus?.sha256)
    throw new Error('Labels must match the exact report corpus snapshot');
  const byId=new Map();
  for(const label of labels.cases) {
    if (typeof label.id !== 'string' || byId.has(label.id)) throw new Error('Invalid or duplicate label id');
    byId.set(label.id,label);
  }
  const retrieval=[],answers=[];
  let unlabelled=0,unreviewedAnswers=0;
  const known=new Set(report.results.map(r=>r.id));
  if (labels.cases.some(l=>!known.has(l.id))) throw new Error('Label id not present in report');
  for(const result of report.results) {
    const label=byId.get(result.id);
    if (!label) {unlabelled++;continue;}
    const ranking=(result.selectedEvidence ?? []).map(e=>{
      if (!Array.isArray(e.chunkReferences) || e.chunkReferences.length !== 1)
        throw new Error('Selected evidence identity is missing or ambiguous');
      return e.chunkReferences[0];
    });
    retrieval.push(retrievalQuality(ranking,label,Number(kArgument)));
    if (label.answerReview) answers.push(answerQuality(label.answerReview));
    else unreviewedAnswers++;
  }
  console.log(JSON.stringify({labelVersion:labels.labelVersion ?? null,reviewer:labels.reviewer ?? null,
    reviewStatus:labels.reviewStatus ?? 'UNSPECIFIED',clinicalReview:labels.clinicalReview ?? 'UNSPECIFIED',
    split:labels.split ?? 'UNSPECIFIED',
    reportCases:report.results.length,unlabelled,unreviewedAnswers,
    summary:summarizeQuality(retrieval,answers)},null,2));
} catch(error) {
  // Do not print label bodies or patient text on parse errors.
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.message);
  process.exitCode=1;
}
