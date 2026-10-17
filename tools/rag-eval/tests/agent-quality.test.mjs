import {test} from 'node:test';
import assert from 'node:assert/strict';
import {agentQuality,agentFingerprint} from '../agent-quality.mjs';
function fixture(){
  const input={messages:[{id:'m1',role:'USER',content:'Synthetic input, not medical information.'}]};
  const dataset={schemaVersion:1,id:'synthetic',split:'DEVELOPMENT',cases:[{id:'one',input},{id:'two',input}]};
  const run={schemaVersion:1,dataset,configuration:{model:'synthetic',promptVersion:'v1',policyVersion:'v1'},results:[{
    id:'one',traceId:'b7dd4067-4281-445a-bc2a-c393953d2421',inputSha256:agentFingerprint(input),
    status:'COMPLETED',intents:['GENERAL_HEALTH'],tools:[],elapsedMs:2,inputTokens:0,outputTokens:null,tokenUsageSource:'PROVIDER_REPORTED'}]};
  const labels={schemaVersion:1,datasetSha256:agentFingerprint(dataset),runSha256:agentFingerprint(run),
    reviewer:{id:'fixture-author',kind:'AUTHOR_ENGINEERING'},cases:[{id:'one',intents:['GENERAL_HEALTH'],tools:[]}]};
  return {run,labels};
}
test('exact labels preserve unknown and measured zero without inventing clinical scores',()=>{
  const {run,labels}=fixture(),result=agentQuality(run,labels);
  assert.equal(result.missingRuns,1);assert.equal(result.intents.exactMatch,1);
  assert.equal(result.toolSelection.microPrecision,null);assert.equal(result.providerUsage.inputTokens,0);
  assert.equal(result.providerUsage.outputTokens,null);assert.equal(result.reviewStatus,'AUTHOR_ENGINEERING');
  assert.equal(result.latencyMs.p95,2);assert.ok(!JSON.stringify(result).includes('Synthetic input'));
});
test('failed model runs remain in completion denominator, not perfect empty selections',()=>{
  const {run,labels}=fixture();run.results[0].status='UNAVAILABLE';run.results[0].intents=[];
  labels.runSha256=agentFingerprint(run);
  const result=agentQuality(run,labels);
  assert.equal(result.reviewedUnavailable,1);assert.equal(result.reviewedCompletionRate,0);
  assert.equal(result.intents.exactMatch,null);assert.equal(result.toolSelection.exactMatch,null);
});
test('dataset, prompt, output, source or trace drift cannot reuse labels',()=>{
  for(const mutate of [r=>r.dataset.cases[0].input.messages[0].content='changed',r=>r.configuration.promptVersion='v2',
    r=>r.results[0].intents=['HUMAN_HELP'],r=>r.results[0].traceId='not-a-trace']){
    const {run,labels}=fixture();mutate(run);assert.throws(()=>agentQuality(run,labels));
  }
});
test('unreviewed results cannot acquire a score from model declarations',()=>{
  const {run,labels}=fixture();labels.cases=[];
  const result=agentQuality(run,labels);assert.equal(result.unreviewed,1);assert.equal(result.intents.evaluated,0);
});
test('whitelists, duplicated labels and spoofed input hashes are rejected',()=>{
  for(const mutate of [r=>r.results[0].tools=[{name:'book',success:true}],r=>r.results.push({...r.results[0]}),
    r=>r.results[0].inputSha256='0'.repeat(64),r=>r.results[0].intents=['GENERAL_HEALTH','GENERAL_HEALTH']]){
    const {run,labels}=fixture();mutate(run);labels.runSha256=agentFingerprint(run);
    assert.throws(()=>agentQuality(run,labels));
  }
});
test('tool selection quality and execution failures are independent',()=>{
  const {run,labels}=fixture();run.results[0].tools=[{name:'medical_knowledge_retrieve',success:false}];
  labels.runSha256=agentFingerprint(run);labels.cases[0].tools=['medical_knowledge_retrieve'];
  const result=agentQuality(run,labels);assert.equal(result.toolSelection.exactMatch,1);
  assert.equal(result.toolExecution.failed,1);
});
test('repeated read-only tool queries count as executions, not duplicate selection errors',()=>{
  const {run,labels}=fixture();run.results[0].tools=[{name:'medical_knowledge_retrieve',success:true},{name:'medical_knowledge_retrieve',success:false}];
  labels.runSha256=agentFingerprint(run);labels.cases[0].tools=['medical_knowledge_retrieve'];
  const result=agentQuality(run,labels);assert.equal(result.toolSelection.exactMatch,1);
  assert.equal(result.toolExecution.attempted,2);assert.equal(result.toolExecution.failed,1);
});
test('oversized or malformed patient source windows cannot be scored',()=>{
  const {run,labels}=fixture();run.dataset.cases[0].input.messages[0].content='x'.repeat(4001);
  labels.datasetSha256=agentFingerprint(run.dataset);labels.runSha256=agentFingerprint(run);
  assert.throws(()=>agentQuality(run,labels));
});
test('labelled cases with no run remain visible in completion denominator',()=>{
  const {run,labels}=fixture();labels.cases.push({id:'two',intents:['GENERAL_HEALTH'],tools:[]});
  const result=agentQuality(run,labels);assert.equal(result.reviewedMissing,1);
  assert.equal(result.reviewedCompletionRate,.5);assert.equal(result.cases[1].status,'MISSING_RUN');
});
test('event attribution requires exhaustive source-bound labels and remains optional',()=>{
  const {run,labels}=fixture();
  assert.equal(agentQuality(run,labels).events.evaluatedCases,0);
  const event={symptom:'Synthetic',bodySite:null,onset:null,duration:null,trigger:null,severity:null,
    subject:'SELF',temporality:'CURRENT',assertion:'AFFIRMED',sourceRefs:[{messageId:'m1',quote:'Synthetic input'}]};
  run.results[0].events=[event];labels.runSha256=agentFingerprint(run);
  labels.cases[0].eventReview={exhaustive:true,events:[{...event,assertion:'UNCERTAIN'}]};
  const result=agentQuality(run,labels);
  assert.equal(result.events.evaluatedCases,1);assert.equal(result.events.exactEventF1,0);
  assert.equal(result.events.attribution.subject.value,1);assert.equal(result.events.attribution.assertion.value,0);
});
