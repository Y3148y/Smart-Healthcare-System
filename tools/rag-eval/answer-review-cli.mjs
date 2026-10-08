import {readFileSync} from 'node:fs';
import {scoreAnswerReviewPacket} from './answer-review-packet.mjs';
try {
  const [packetPath]=process.argv.slice(2);
  if(!packetPath) throw new Error('Usage: node answer-review-cli.mjs local-answer-review.json');
  console.log(JSON.stringify(scoreAnswerReviewPacket(JSON.parse(readFileSync(packetPath,'utf8'))),null,2));
} catch(error) {
  console.error(error instanceof SyntaxError?'Invalid JSON input':error.message);
  process.exitCode=1;
}
