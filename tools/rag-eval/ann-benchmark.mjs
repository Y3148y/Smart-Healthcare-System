import {randomUUID} from 'node:crypto'
import {mkdirSync,writeFileSync} from 'node:fs'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
import {performance} from 'node:perf_hooks'
import {reviewOutputPath} from './review-output-path.mjs'

export function configuration(args) {
  const values=new Map()
  for(let i=0;i<args.length;i++) {
    const name=args[i]
    if(!['--apply','--url','--output','--points','--dimensions','--queries','--k','--ef'].includes(name)||values.has(name)) throw Error('Unknown or duplicate benchmark option')
    if(name==='--apply')values.set(name,true)
    else {if(!args[i+1]||args[i+1].startsWith('--'))throw Error('Missing benchmark option');values.set(name,args[++i])}
  }
  if(!values.get('--apply')||!values.get('--url')||!values.get('--output'))throw Error('Explicit --apply, --url and --output are required')
  const url=new URL(values.get('--url'))
  if(url.protocol!=='http:'||!['127.0.0.1','localhost','[::1]'].includes(url.hostname)||url.username||url.password||url.search||url.hash||url.pathname!=='/')throw Error('Only a clean loopback HTTP origin is allowed')
  const integer=(name,fallback,min,max)=>{const v=Number(values.get(name)??fallback);if(!Number.isSafeInteger(v)||v<min||v>max)throw Error('Invalid benchmark size');return v}
  const points=integer('--points',10000,1000,50000),dimensions=integer('--dimensions',32,8,256),queries=integer('--queries',40,10,200),k=integer('--k',10,1,50),ef=integer('--ef',64,4,512)
  if(ef<k)throw Error('ef must be at least k')
  return {url:url.origin,output:values.get('--output'),points,dimensions,queries,k,ef,seed:314159}
}
export function vectorGenerator(seed,dimensions) {
  let state=seed>>>0
  return ()=>Array.from({length:dimensions},()=>{state=(Math.imul(state,1664525)+1013904223)>>>0;return state/4294967296*2-1})
}
export function summarize(samples,k) {
  if(!samples.length)throw Error('No ANN query samples')
  const quantile=(values,q)=>[...values].sort((a,b)=>a-b)[Math.ceil(values.length*q)-1]
  for(const s of samples)if(s.exactIds.length!==k||s.annIds.length!==k||new Set(s.exactIds).size!==k||new Set(s.annIds).size!==k
    ||![s.exactMs,s.annMs].every(x=>Number.isFinite(x)&&x>=0))throw Error('Incomplete ANN sample')
  return {queries:samples.length,k,recallAtK:samples.reduce((n,s)=>n+s.annIds.filter(id=>s.exactIds.includes(id)).length/k,0)/samples.length,
    exactLatencyMs:{p50:quantile(samples.map(x=>x.exactMs),.5),p95:quantile(samples.map(x=>x.exactMs),.95)},
    annLatencyMs:{p50:quantile(samples.map(x=>x.annMs),.5),p95:quantile(samples.map(x=>x.annMs),.95)}}
}

export async function benchmark(config) {
  // The CLI cannot target or delete an existing collection: it owns exactly one random collection.
  const collection='test_ann_'+randomUUID().replaceAll('-',''),endpoint=config.url+'/collections/'+collection
  let creationAttempted=false,summary
  const request=async(method,url,body)=>{
    const response=await fetch(url,{method,redirect:'error',headers:{'Content-Type':'application/json'},
      body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)})
    if(!response.ok)throw Error('ANN fixture '+method+' '+new URL(url).pathname.replace(collection,'<owned-collection>')+' HTTP '+response.status)
    const payload=await response.json()
    if(payload.status!=='ok')throw Error('ANN fixture operation was not acknowledged')
    return payload.result
  }
  const begun=performance.now()
  try {
    const existing=await fetch(endpoint,{redirect:'error',signal:AbortSignal.timeout(5000)})
    if(existing.status!==404)throw Error('Random benchmark collection is not absent')
    // Creation may commit even if its reply is lost; cleanup must also cover uncertain replies.
    creationAttempted=true
    await request('PUT',endpoint,{vectors:{size:config.dimensions,distance:'Cosine'},
      hnsw_config:{m:16,ef_construct:100,full_scan_threshold:10},optimizers_config:{indexing_threshold:1,default_segment_number:1}})
    const next=vectorGenerator(config.seed,config.dimensions)
    for(let offset=0;offset<config.points;offset+=250) {
      const points=Array.from({length:Math.min(250,config.points-offset)},(_,index)=>({id:offset+index,vector:next()}))
      await request('PUT',endpoint+'/points?wait=true',{points})
    }
    const deadline=performance.now()+(config.indexWaitMs??180000)
    let state
    do {
      state=await request('GET',endpoint)
      if(state.status==='green'&&state.points_count===config.points&&state.indexed_vectors_count===config.points)break
      if(performance.now()>deadline)throw Error('ANN indexing did not complete; no benchmark result is valid')
      await new Promise(resolve=>setTimeout(resolve,500))
    } while(true)
    if(state.config.hnsw_config.full_scan_threshold!==10||state.config.params.vectors.size!==config.dimensions)throw Error('Actual collection configuration differs')
    const buildElapsedMs=performance.now()-begun,query=vectorGenerator(config.seed+1,config.dimensions),samples=[]
    const search=async(vector,exact)=>{
      const started=performance.now()
      const result=await request('POST',endpoint+'/points/search',{vector,limit:config.k,with_payload:false,with_vector:false,
        params:exact?{exact:true}:{exact:false,indexed_only:true,hnsw_ef:config.ef}})
      return {ids:result.map(item=>String(item.id)),elapsedMs:performance.now()-started}
    }
    // Warm both modes; alternate measured execution order so one mode is not always first.
    const warm=query();await search(warm,true);await search(warm,false)
    for(let i=0;i<config.queries;i++) {
      const vector=query(),pair={}
      for(const exact of i%2?[false,true]:[true,false])pair[exact?'exact':'ann']=await search(vector,exact)
      samples.push({exactIds:pair.exact.ids,annIds:pair.ann.ids,exactMs:pair.exact.elapsedMs,annMs:pair.ann.elapsedMs})
    }
    const after=await request('GET',endpoint)
    if(after.points_count!==config.points||after.indexed_vectors_count!==config.points)throw Error('Collection changed during benchmark')
    summary={schemaVersion:1,kind:'SYNTHETIC_ANN_VS_EXACT',createdAt:new Date().toISOString(),
      configuration:{points:config.points,dimensions:config.dimensions,queries:config.queries,k:config.k,ef:config.ef,seed:config.seed},
      actual:{status:state.status,points:state.points_count,indexedVectors:state.indexed_vectors_count,segments:state.segments_count,
        vectors:state.config.params.vectors,hnsw:state.config.hnsw_config,optimizer:state.config.optimizer_config},
      buildElapsedMs,vectorPayloadBytes:config.points*config.dimensions*4,...summarize(samples,config.k),samples,
      limitations:['Deterministic synthetic vectors, not medical retrieval labels or LLM evaluation.',
        'Latency includes local HTTP; no CPU/RAM/disk capacity or production throughput claim.',
        'Indexed count, indexed_only and 10KB full-scan threshold constrain ANN eligibility; no per-query planner trace.']}
  } finally {
    if(creationAttempted) {
      if(!/^test_ann_[a-f0-9]{32}$/.test(collection))throw Error('Unsafe benchmark cleanup target')
      const present=await fetch(endpoint,{redirect:'error',signal:AbortSignal.timeout(5000)})
      if(present.status===200) {
        const current=(await present.json()).result
        if(current?.config?.params?.vectors?.size!==config.dimensions||current?.config?.params?.vectors?.distance!=='Cosine')
          throw Error('Owned fixture configuration cannot be verified for cleanup')
        await request('DELETE',endpoint)
      } else if(present.status!==404)throw Error('Owned fixture existence cannot be verified for cleanup')
      const removed=await fetch(endpoint,{redirect:'error',signal:AbortSignal.timeout(5000)})
      if(removed.status!==404)throw Error('Synthetic collection cleanup could not be confirmed')
      if(summary)summary.syntheticCollectionRemoved=true
    }
  }
  return summary
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const config=configuration(process.argv.slice(2)),root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
    mkdirSync(resolve(root,'reports/rag'),{recursive:true})
    const output=reviewOutputPath(root,config.output)
    const result=await benchmark(config)
    writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
    console.log(JSON.stringify({output,points:result.actual.points,indexed:result.actual.indexedVectors,recallAtK:result.recallAtK,
      exactLatencyMs:result.exactLatencyMs,annLatencyMs:result.annLatencyMs,cleaned:result.syntheticCollectionRemoved}))
  } catch(error) { console.error(error.code?'ANN benchmark file/transport failure':error.message);process.exitCode=1 }
}
