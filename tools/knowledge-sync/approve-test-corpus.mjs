import {readdirSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {approveTestDocument,readTestCorpus} from './lib/test-corpus-approval.mjs';

const root=resolve(fileURLToPath(new URL('../../knowledge/corpus/',import.meta.url)));
function documents(dir) {
  return readdirSync(dir,{withFileTypes:true}).flatMap(entry=>{
    const path=join(dir,entry.name);
    return entry.isDirectory()?documents(path):entry.isFile() && entry.name==='document.zh-CN.md'?[path]:[];
  }).sort();
}

try {
  const args=process.argv.slice(2);
  if(args.length>1 || args.some(arg=>arg!=='--apply')) throw new Error('USAGE: node approve-test-corpus.mjs [--apply]');
  const packets=readTestCorpus(documents(root));
  if(!args.includes('--apply')) {
    console.log(JSON.stringify({status:'VALIDATED_NOT_APPROVED',documents:packets.length}));
  } else {
    if(process.env.KNOWLEDGE_TEST_APPROVAL!=='YES') throw new Error('TEST_APPROVAL_OPT_IN_REQUIRED');
    if(!process.env.KNOWLEDGE_IMPORT_BASE_URL) throw new Error('EXPLICIT_TEST_BACKEND_URL_REQUIRED');
    const results=[];
    for(const packet of packets) results.push(await approveTestDocument(packet,{
      base:process.env.KNOWLEDGE_IMPORT_BASE_URL,token:process.env.KNOWLEDGE_ADMIN_TOKEN}));
    console.log(JSON.stringify({status:'LOCAL_TEST_APPROVED_NOT_CLINICALLY_REVIEWED',documents:results}));
  }
} catch(error) {
  // No token, source text, provider body or patient data in CLI errors.
  console.error(/^[A-Z0-9_ :.[\]-]+$/.test(error.message)?error.message:'TEST_CORPUS_APPROVAL_FAILED');
  process.exitCode=1;
}
