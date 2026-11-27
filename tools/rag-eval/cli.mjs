import {mkdirSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {validateExpectedTitles, evaluateCase, summarize, reportFileName} from './lib.mjs';
import {loadEvaluationInput,runIntegrity,runtimeIdentity} from './evaluation-input.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
let dataset,datasetSha256;
try {
  ({dataset,datasetSha256}=loadEvaluationInput(process.argv.slice(2),{
    directory,reportsDirectory:resolve(directory,'../../reports/rag')}));
} catch {
  console.error('EVALUATION_INPUT_INVALID');
  process.exit(2);
}
const base = new URL(process.env.RAG_EVAL_BASE_URL || 'http://127.0.0.1:8081/api/');
if (!['127.0.0.1','localhost','[::1]'].includes(base.hostname) || base.username || base.password || !['http:','https:'].includes(base.protocol))
  throw new Error('Evaluation login is restricted to the local backend');
if (!base.pathname.endsWith('/')) base.pathname += '/';
let token;
async function request(path, options = {}) {
  const response = await fetch(new URL(path,base), {...options,
    headers:{'Content-Type':'application/json',...(token ? {Authorization:`Bearer ${token}`} : {})},
    signal:AbortSignal.timeout(45000), redirect:'error'});
  if (!response.ok) throw new Error(`Evaluation request failed HTTP ${response.status}`);
  return response.json();
}
async function snapshot() {
  const snapshot=await request('admin/knowledge/retrieval-snapshot');
  if(snapshot.scope!=='ACTIVE_RETRIEVAL' || !Array.isArray(snapshot.documents)) throw new Error('Active retrieval corpus snapshot required');
  const approved = [];
  for (const document of snapshot.documents.sort((a,b)=>a.id.localeCompare(b.id))) {
    approved.push({id:document.id,title:document.title,chunks:document.chunks.map(c=>({chunkId:c.chunkId,documentVersion:c.documentVersion,
      contentSha256:c.contentSha256,chunkingVersion:c.chunkingVersion,
      sectionPath:c.sectionPath,ordinal:c.ordinal,start:c.start,end:c.end,positionUnit:c.positionUnit,
      text:c.text,source:c.source}))});
  }
  return {approved,sha256:createHash('sha256').update(JSON.stringify(approved)).digest('hex'),
    scope:snapshot.scope,sourceRevision:snapshot.sourceRevision,publicationVersion:snapshot.publicationVersion};
}
try {
  const password=process.env.RAG_EVAL_PASSWORD;
  if (typeof password !== 'string' || !password.trim())
    throw new Error('RAG_EVAL_PASSWORD must be supplied through the process environment');
  const login = await request('auth/login',{method:'POST',body:JSON.stringify({username:process.env.RAG_EVAL_USERNAME || 'admin',password})});
  token = login.token;
  if (typeof token !== 'string' || !token) throw new Error('Evaluation authentication failed');
  const publicationBefore=(await request('admin/knowledge/releases')).state;
  const runtimeBefore=await request('admin/knowledge/runtime');
  const before = await snapshot(), results = [];
  validateExpectedTitles(dataset, before.approved);
  for (const item of dataset.cases) results.push(evaluateCase(item, {
    ...await request(`admin/knowledge/search/details?q=${encodeURIComponent(item.query)}`), corpus:before,
  }));
  const after = await snapshot();
  const runtimeAfter=await request('admin/knowledge/runtime');
  const publicationAfter=(await request('admin/knowledge/releases')).state;
  const integrity=runIntegrity({before,after,runtimeBefore,runtimeAfter,publicationBefore,publicationAfter});
  const summary = {...summarize(results),...integrity};
  const output = resolve(directory, '../../reports/rag', reportFileName(dataset.labelVersion, randomUUID()));
  mkdirSync(dirname(output),{recursive:true});
  writeFileSync(output,JSON.stringify({schemaVersion:1,time:new Date().toISOString(),labelVersion:dataset.labelVersion,datasetSha256,
    split:dataset.split ?? 'UNSPECIFIED',labelAuthor:dataset.labelAuthor ?? 'UNSPECIFIED',
    reviewStatus:dataset.reviewStatus,corpus:before,runtime:runtimeAfter,
    observations:{runtimeBefore:runtimeIdentity(runtimeBefore),runtimeAfter:runtimeIdentity(runtimeAfter),
      publicationBefore,publicationAfter,corpusBeforeSha256:before.sha256,corpusAfterSha256:after.sha256,
      corpusAfterObservation:{scope:after.scope,sourceRevision:after.sourceRevision,publicationVersion:after.publicationVersion},
      limitation:'Checks only API-exposed fields at both boundaries; not an atomic snapshot or full configuration attestation.'},
    summary,results},null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({summary,output}));
  process.exitCode = summary.failedIds.length || Object.values(integrity).some(stable=>!stable) ? 1 : 0;
} catch(error) {
  console.error(error.message);
  process.exitCode = 2;
}
