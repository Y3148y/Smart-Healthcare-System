import {readFileSync,writeFileSync} from 'node:fs';
import {reviewPacket} from './review-packet.mjs';
try {
  const [input,output]=process.argv.slice(2);
  if (!input || !output) throw new Error('Usage: node review-packet-cli.mjs report.json new-local-review.json');
  const packet=reviewPacket(JSON.parse(readFileSync(input,'utf8')));
  writeFileSync(output,JSON.stringify(packet,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({cases:packet.cases.length,chunks:packet.corpus.length,reviewStatus:packet.reviewStatus}));
} catch(error) {
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.message);
  process.exitCode=1;
}
