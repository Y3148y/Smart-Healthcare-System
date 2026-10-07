import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {validateCases, evaluateCase, summarize, reportFileName} from './lib.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const datasetPath = resolve(directory, process.argv[2] || 'cases.json');
if (dirname(datasetPath) !== directory) throw new Error('Evaluation dataset must be inside tools/rag-eval');
const dataset = validateCases(JSON.parse(readFileSync(datasetPath, 'utf8')));
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
  const documents = await request('admin/knowledge');
  const approved = [];
  for (const document of documents.filter(d=>d.status==='READY').sort((a,b)=>a.id.localeCompare(b.id))) {
    const chunks = await request(`admin/knowledge/${encodeURIComponent(document.id)}/chunks`);
    approved.push({id:document.id,title:document.title,chunks:chunks.map(c=>({chunkId:c.chunkId,documentVersion:c.documentVersion,
      contentSha256:c.contentSha256,chunkingVersion:c.chunkingVersion}))});
  }
  return {approved,sha256:createHash('sha256').update(JSON.stringify(approved)).digest('hex')};
}
try {
  const login = await request('auth/login',{method:'POST',body:JSON.stringify({username:process.env.RAG_EVAL_USERNAME || 'admin',password:process.env.RAG_EVAL_PASSWORD || '123456'})});
  token = login.token;
  if (typeof token !== 'string' || !token) throw new Error('Evaluation authentication failed');
  const before = await snapshot(), results = [];
  for (const item of dataset.cases) results.push(evaluateCase(item, await request(`admin/knowledge/search/details?q=${encodeURIComponent(item.query)}`)));
  const after = await snapshot();
  const corpusStable = before.sha256 === after.sha256;
  const summary = {...summarize(results),corpusStable};
  const output = resolve(directory, '../../.codex-rag-evaluation', reportFileName(dataset.labelVersion, randomUUID()));
  mkdirSync(dirname(output),{recursive:true});
  writeFileSync(output,JSON.stringify({schemaVersion:1,time:new Date().toISOString(),labelVersion:dataset.labelVersion,
    split:dataset.split ?? 'UNSPECIFIED',labelAuthor:dataset.labelAuthor ?? 'UNSPECIFIED',
    reviewStatus:dataset.reviewStatus,corpus:before,runtime:await request('admin/knowledge/runtime'),summary,results},null,2)+'\n');
  console.log(JSON.stringify({summary,output}));
  process.exitCode = summary.failedIds.length || !corpusStable ? 1 : 0;
} catch(error) {
  console.error(error.message);
  process.exitCode = 2;
}
