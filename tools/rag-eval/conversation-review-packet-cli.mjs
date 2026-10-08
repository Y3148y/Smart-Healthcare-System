import {readFileSync,writeFileSync} from 'node:fs';
import {answerReviewPacket} from './answer-review-packet.mjs';
try {
  const [conversationPath,outputPath]=process.argv.slice(2);
  if(!conversationPath||!outputPath) throw new Error('Usage: node conversation-review-packet-cli.mjs local-conversation.json new-review-packet.json');
  const packet=answerReviewPacket(JSON.parse(readFileSync(conversationPath,'utf8')));
  writeFileSync(outputPath,JSON.stringify(packet,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({cases:packet.cases.length,reviewable:packet.cases.filter(c=>c.reviewable).length,
    reviewStatus:packet.reviewStatus,output:outputPath}));
} catch(error) {
  console.error(error instanceof SyntaxError?'Invalid JSON input':error.message);
  process.exitCode=1;
}
