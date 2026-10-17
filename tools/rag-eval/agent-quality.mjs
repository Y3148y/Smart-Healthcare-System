import {createHash} from 'node:crypto';

// Offline only: labels are supplied by a reviewer, never inferred from model self-ratings.
const INTENTS=new Set(['GENERAL_HEALTH','SYMPTOM_CONSULT','FIND_DEPARTMENT','FIND_DOCTOR','BOOKING_REQUEST','HUMAN_HELP']);
const TOOLS=new Set(['medical_knowledge_retrieve','department_search','doctor_schedule_search']);
const ID=/^[A-Za-z0-9_-]{1,80}$/;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const fail=()=>{throw new Error('Invalid or mismatched Agent evaluation input');};
const ratio=(n,d)=>d?n/d:null;
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'
  ?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
export const agentFingerprint=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
function unique(items,allowed){
  if(!Array.isArray(items)||items.some(item=>!allowed.has(item))||new Set(items).size!==items.length)fail();
  return new Set(items);
}
function indexed(items){
  if(!Array.isArray(items))fail();
  const map=new Map();
  for(const item of items){if(!ID.test(item?.id??'')||map.has(item.id))fail();map.set(item.id,item);}
  return map;
}
function setScore(actual,expected){
  const hits=[...actual].filter(item=>expected.has(item)).length;
  return {exact:hits===actual.size&&hits===expected.size,hits,predicted:actual.size,expected:expected.size};
}
function summarize(rows){
  const hits=rows.reduce((n,row)=>n+row.hits,0),predicted=rows.reduce((n,row)=>n+row.predicted,0),expected=rows.reduce((n,row)=>n+row.expected,0);
  return {evaluated:rows.length,exactMatch:ratio(rows.filter(row=>row.exact).length,rows.length),
    microPrecision:ratio(hits,predicted),microRecall:ratio(hits,expected),
    microF1:ratio(2*hits,predicted+expected)};
}
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1]:null;

export function agentQuality(report,labels){
  if(report?.schemaVersion!==1||labels?.schemaVersion!==1||report.dataset?.schemaVersion!==1
      ||!ID.test(report.dataset?.id??'')||!['DEVELOPMENT','HOLDOUT'].includes(report.dataset?.split)
      ||labels.datasetSha256!==agentFingerprint(report.dataset)||labels.runSha256!==agentFingerprint(report)
      ||!['AUTHOR_ENGINEERING','INDEPENDENT_ENGINEERING'].includes(labels.reviewer?.kind)
      ||!ID.test(labels.reviewer?.id??''))fail();
  const configuration=report.configuration;
  if(!configuration||['model','promptVersion','policyVersion'].some(key=>typeof configuration[key]!=='string'||!configuration[key].trim()))fail();
  const sources=indexed(report.dataset.cases),runs=indexed(report.results),reviews=indexed(labels.cases);
  for(const source of sources.values()){
    const messages=source.input?.messages;
    if(!Array.isArray(messages)||!messages.length||messages[0]?.role!=='USER'||messages.at(-1)?.role!=='USER'
        ||messages.some(m=>!ID.test(m?.id??'')||!['USER','ASSISTANT'].includes(m.role)||typeof m.content!=='string'||!m.content.trim())
        ||new Set(messages.map(m=>m.id)).size!==messages.length
        ||messages.filter(m=>m.role==='USER').length>5
        ||messages.reduce((n,m)=>n+m.content.length,0)>4000)fail();
  }
  for(const id of runs.keys())if(!sources.has(id))fail();
  for(const [id,review] of reviews){
    if(!sources.has(id))fail();
    unique(review.intents,INTENTS);unique(review.tools,TOOLS);
  }
  const intents=[],tools=[],latencies=[],cases=[];let unavailable=0,missingRuns=0,reviewedMissing=0,unreviewed=0,failedTools=0,toolExecutions=0;
  let knownInput=0,knownOutput=0,inputSum=0,outputSum=0;
  const traces=new Set();
  for(const [id,source] of sources){
    const run=runs.get(id),review=reviews.get(id);
    if(!run){missingRuns++;if(review)reviewedMissing++;cases.push({id,status:'MISSING_RUN'});continue;}
    if(!UUID.test(run.traceId??'')||traces.has(run.traceId)||run.inputSha256!==agentFingerprint(source.input)
        ||!['COMPLETED','UNAVAILABLE','INVALID'].includes(run.status)
        ||!Number.isFinite(run.elapsedMs)||run.elapsedMs<0)fail();
    traces.add(run.traceId);latencies.push(run.elapsedMs);
    if(!Array.isArray(run.tools)||run.tools.some(tool=>!TOOLS.has(tool?.name)))fail();
    const selected=new Set(run.tools.map(tool=>tool.name)); // Distinct queries may legitimately repeat a tool.
    for(const tool of run.tools){if(typeof tool.success!=='boolean')fail();toolExecutions++;if(!tool.success)failedTools++;}
    const proposed=unique(run.intents,INTENTS);
    for(const [key,counter] of [['inputTokens','input'],['outputTokens','output']]){
      const value=run[key];
      if(value!=null&&(!Number.isSafeInteger(value)||value<0))fail();
      if(run.tokenUsageSource==='PROVIDER_REPORTED'&&value!=null){
        if(counter==='input'){knownInput++;inputSum+=value;}else{knownOutput++;outputSum+=value;}
      }
    }
    if(!['PROVIDER_REPORTED','NOT_REPORTED','NOT_APPLICABLE','LEGACY_RECORDED'].includes(run.tokenUsageSource))fail();
    if(!review){unreviewed++;cases.push({id,status:'UNREVIEWED'});continue;}
    const expectedIntent=unique(review.intents,INTENTS),expectedTools=unique(review.tools,TOOLS);
    if(run.status!=='COMPLETED'){
      unavailable++;cases.push({id,status:run.status});continue;
    }
    const intent=setScore(proposed,expectedIntent),tool=setScore(selected,expectedTools);
    intents.push(intent);tools.push(tool);cases.push({id,status:'REVIEWED',intents:intent,tools:tool});
  }
  return {schemaVersion:1,datasetId:report.dataset.id,split:report.dataset.split,
    datasetSha256:labels.datasetSha256,runSha256:labels.runSha256,reviewStatus:labels.reviewer.kind,
    totalCases:sources.size,runCases:runs.size,missingRuns,reviewedMissing,unreviewed,reviewedUnavailable:unavailable,
    runStatuses:Object.fromEntries(['COMPLETED','UNAVAILABLE','INVALID'].map(status=>[status,
      report.results.filter(run=>run.status===status).length])),
    intents:summarize(intents),toolSelection:summarize(tools),
    reviewedCompletionRate:ratio(intents.length,intents.length+unavailable+reviewedMissing),
    toolExecution:{attempted:toolExecutions,failed:failedTools},
    latencyMs:{p50:percentile(latencies,.5),p95:percentile(latencies,.95),measured:latencies.length},
    providerUsage:{inputTokens:knownInput?inputSum:null,outputTokens:knownOutput?outputSum:null,
      inputMeasuredCases:knownInput,outputMeasuredCases:knownOutput},cases,
    interpretation:'Offline exact-set engineering labels; failed/unreviewed cases stay separate. Not clinical accuracy, semantic fact attribution, or a billing receipt.'};
}
