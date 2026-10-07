import {readFileSync,statSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {parseMarkdownDocument,inspectMarkdownDocument} from './markdown-document.mjs';

export function readMarkdownFile(path) {
  const read=p=>{
    if(statSync(p).size>4*1024*1024) throw new Error('DOCUMENT_TOO_LARGE');
    return new TextDecoder('utf-8',{fatal:true}).decode(readFileSync(p));
  };
  const text=read(path);
  const match=/^---\r?\n([\s\S]*?)\r?\n---\r?\n/.exec(text.replace(/^\uFEFF/,''));
  if(!match) throw new Error('DOCUMENT_FRONT_MATTER_REQUIRED');
  let header;
  try {header=JSON.parse(match[1]);} catch {throw new Error('INVALID_DOCUMENT_FRONT_MATTER');}
  const root=dirname(resolve(path)),source=read(resolve(root,'source.en.md'));
  let provenance,additional={};
  if(header.provenanceFile!==undefined) {
    if(header.provenanceFile!=='provenance.json') throw new Error('INVALID_PROVENANCE_PATH');
    provenance=read(resolve(root,'provenance.json'));
    let audit;
    try {audit=JSON.parse(provenance);} catch {throw new Error('INVALID_PROVENANCE');}
    if(!Array.isArray(audit.sourceSnapshots)) throw new Error('INVALID_PROVENANCE');
    for(const item of audit.sourceSnapshots) {
      if(!/^source(?:\.[a-z]+)?\.en\.md$/.test(item.file??'')) throw new Error('INVALID_PROVENANCE_PATH');
      if(item.file!=='source.en.md') additional[item.file]=read(resolve(root,item.file));
    }
  }
  return {packet:parseMarkdownDocument(text,provenance),inspection:inspectMarkdownDocument(text,source,provenance,additional)};
}
