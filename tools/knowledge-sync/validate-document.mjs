import {readFileSync,statSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {inspectMarkdownDocument} from './lib/markdown-document.mjs';

// Offline inspection only: no login, network, database writes or approval.
try {
  const paths=process.argv.slice(2);
  if(!paths.length) throw new Error('DOCUMENT_PATH_REQUIRED');
  for(const path of paths) {
    if(statSync(path).size>4*1024*1024) throw new Error('DOCUMENT_TOO_LARGE');
    const decode=p=>new TextDecoder('utf-8',{fatal:true}).decode(readFileSync(p));
    console.log(JSON.stringify(inspectMarkdownDocument(decode(path),decode(resolve(dirname(path),'source.en.md')))));
  }
} catch(error) {
  console.error(/^[A-Z0-9_]+$/.test(error.message)?error.message:'DOCUMENT_VALIDATION_FAILED');
  process.exitCode=1;
}
