// Local input only; emits technical metrics, not patient messages, prompts or model bodies.
import {readFileSync,statSync} from 'node:fs';
import {agentQuality} from './agent-quality.mjs';
try{
  const args=process.argv.slice(2);
  if(args.length!==2)throw new Error('Expected run and reviewed-label files');
  const load=file=>{if(statSync(file).size>16*1024*1024)throw new Error('Input exceeds limit');return JSON.parse(readFileSync(file,'utf8'));};
  console.log(JSON.stringify(agentQuality(load(args[0]),load(args[1])),null,2));
}catch{
  console.error('Agent evaluation failed: provide bounded local JSON files with matching dataset/run fingerprints and explicit review labels.');
  process.exitCode=1;
}
