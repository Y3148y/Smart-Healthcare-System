import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {compareRetrievalRuns} from './ablation.mjs';
import {reviewOutputPath} from './review-output-path.mjs';

try {
  const args=process.argv.slice(2), reports=[];
  let labels=null,output=null,k=3;
  const flags=new Set();
  for (let i=0;i<args.length;i++) {
    const value=args[i];
    if (['--labels','--output','--k'].includes(value)) {
      if (flags.has(value) || !args[i+1] || args[i+1].startsWith('--')) throw new Error('Missing or duplicate ablation option');
      flags.add(value);
    }
    if (value==='--labels') labels=args[++i];
    else if (value==='--output') output=args[++i];
    else if (value==='--k') k=Number(args[++i]);
    else if (value.startsWith('--')) throw new Error('Unknown ablation option');
    else reports.push(value);
  }
  if (!reports.length || !output) throw new Error('Usage: node ablation-cli.mjs report.json [other-report.json] --output reports/rag/comparison.json [--labels labels.json] [--k 3]');
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
  mkdirSync(resolve(root,'reports/rag'),{recursive:true});
  const destination=reviewOutputPath(root,output);
  const result=compareRetrievalRuns(reports.map(path=>JSON.parse(readFileSync(path,'utf8'))),
    labels ? JSON.parse(readFileSync(labels,'utf8')) : null,{k});
  writeFileSync(destination,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({output:destination,runs:result.runs.length,labelledCases:result.runs[0].labelledCases}));
} catch (error) {
  console.error(error instanceof SyntaxError ? 'Invalid JSON input' : error.code ? 'Ablation file operation failed' : error.message);
  process.exitCode=1;
}
