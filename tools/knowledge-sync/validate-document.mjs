import {readMarkdownFile} from './lib/markdown-file.mjs';
import {readdirSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

// Offline inspection only: no login, network, database writes or approval.
try {
  const args=process.argv.slice(2);
  let paths=args;
  if(args.length===1 && args[0]==='--corpus') {
    const root=fileURLToPath(new URL('../../knowledge/corpus/',import.meta.url));
    paths=[];
    for(const category of readdirSync(root,{withFileTypes:true}).filter(item=>item.isDirectory()))
      for(const topic of readdirSync(join(root,category.name),{withFileTypes:true}).filter(item=>item.isDirectory()))
        paths.push(join(root,category.name,topic.name,'document.zh-CN.md'));
    paths.sort();
  }
  if(!paths.length) throw new Error('DOCUMENT_PATH_REQUIRED');
  const inspections=paths.map(path=>readMarkdownFile(path).inspection);
  if(args.length===1 && args[0]==='--corpus')
    console.log(JSON.stringify({documents:inspections.length,allSourceVerified:inspections.every(item=>item.sourceVerified),
      reviewStatuses:[...new Set(inspections.map(item=>item.status))].sort()}));
  else inspections.forEach(item=>console.log(JSON.stringify(item)));
} catch(error) {
  console.error(/^[A-Z0-9_]+$/.test(error.message)?error.message:'DOCUMENT_VALIDATION_FAILED');
  process.exitCode=1;
}
