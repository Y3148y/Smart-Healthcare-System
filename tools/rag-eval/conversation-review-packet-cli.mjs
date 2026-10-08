import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname,resolve} from 'node:path';
import {answerReviewPacket} from './answer-review-packet.mjs';
import {reviewOutputPath} from './review-output-path.mjs';
try {
  const [conversationPath,outputPath]=process.argv.slice(2);
  if(!conversationPath||!outputPath) throw new Error('Usage: node tools/rag-eval/conversation-review-packet-cli.mjs local-conversation.json reports/rag/new-review-packet.json');
  const packet=answerReviewPacket(JSON.parse(readFileSync(conversationPath,'utf8')));
  const projectRoot=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
  const safeOutput=reviewOutputPath(projectRoot,outputPath);
  writeFileSync(safeOutput,JSON.stringify(packet,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({cases:packet.cases.length,reviewable:packet.cases.filter(c=>c.reviewable).length,
    reviewStatus:packet.reviewStatus,output:'reports/rag/'+safeOutput.split(/[\\/]/).at(-1)}));
} catch(error) {
  console.error(error instanceof SyntaxError?'Invalid JSON input':error.message);
  process.exitCode=1;
}
