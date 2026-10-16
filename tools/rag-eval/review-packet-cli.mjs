import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {reviewPacket} from './review-packet.mjs';
import {reviewOutputPath} from './review-output-path.mjs';
try {
  const [input,output]=process.argv.slice(2);
  if (!input || !output || process.argv.slice(2).length!==2)
    throw new Error('Usage: node review-packet-cli.mjs report.json reports/rag/new-local-review.json');
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
  mkdirSync(resolve(root,'reports/rag'),{recursive:true});
  const destination=reviewOutputPath(root,output);
  const packet=reviewPacket(JSON.parse(readFileSync(input,'utf8')));
  writeFileSync(destination,JSON.stringify(packet,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({cases:packet.cases.length,chunks:packet.corpus.length,reviewStatus:packet.reviewStatus}));
} catch(error) {
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.code ? 'Review file operation failed' : error.message);
  process.exitCode=1;
}
