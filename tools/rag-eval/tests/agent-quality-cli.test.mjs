import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {agentFingerprint} from '../agent-quality.mjs';

test('offline Agent CLI emits metrics only and failure never echoes input bodies',()=>{
  const dir=mkdtempSync(join(tmpdir(),'agent-quality-'));
  try{
    const input={messages:[{id:'m1',role:'USER',content:'PRIVATE_SYNTHETIC_MARKER'}]};
    const dataset={schemaVersion:1,id:'fixture',split:'DEVELOPMENT',cases:[{id:'one',input}]};
    const report={schemaVersion:1,dataset,configuration:{model:'fixture',promptVersion:'v1',policyVersion:'v1'},results:[{
      id:'one',traceId:'b7dd4067-4281-445a-bc2a-c393953d2421',inputSha256:agentFingerprint(input),status:'COMPLETED',
      intents:['GENERAL_HEALTH'],tools:[],elapsedMs:1,tokenUsageSource:'NOT_REPORTED'}]};
    const labels={schemaVersion:1,datasetSha256:agentFingerprint(dataset),runSha256:agentFingerprint(report),
      reviewer:{id:'fixture',kind:'AUTHOR_ENGINEERING'},cases:[{id:'one',intents:['GENERAL_HEALTH'],tools:[]}]};
    const source=join(dir,'report.json'),review=join(dir,'labels.json');
    writeFileSync(source,JSON.stringify(report));writeFileSync(review,JSON.stringify(labels));
    const cli=fileURLToPath(new URL('../agent-quality-cli.mjs',import.meta.url));
    const run=(extra=[])=>spawnSync(process.execPath,[cli,source,review,...extra],{encoding:'utf8'});
    const good=run();assert.equal(good.status,0,good.stderr);
    assert.equal(JSON.parse(good.stdout).intents.exactMatch,1);
    assert.ok(!good.stdout.includes('PRIVATE_SYNTHETIC_MARKER'));
    assert.equal(run(['extra']).status,1);
    writeFileSync(source,'PRIVATE_SYNTHETIC_MARKER');
    const bad=run();assert.equal(bad.status,1);assert.ok(!bad.stderr.includes('PRIVATE_SYNTHETIC_MARKER'));
  }finally{rmSync(dir,{recursive:true,force:true});}
});
