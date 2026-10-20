import test from 'node:test'
import assert from 'node:assert/strict'
import {configuration,vectorGenerator,summarize,benchmark} from '../ann-benchmark.mjs'
test('ANN benchmark requires explicit isolated writes and bounded options without a caller collection',()=>{
  const args=['--apply','--url','http://127.0.0.1:6333','--output','reports/rag/fixture.json']
  assert.equal(configuration(args).points,10000)
  for(const extra of [['--points','999'],['--points','50001'],['--dimensions','NaN'],['--collection','real'],['--ef','4','--k','10'],['--url','http://remote']])
    assert.throws(()=>configuration([...args,...extra]))
  assert.throws(()=>configuration(args.slice(1)))
  for(const url of ['http://localhost.evil','http://user:password@localhost','http://localhost/path','http://localhost/?x=y'])
    assert.throws(()=>configuration(['--apply','--url',url,'--output','reports/rag/fixture.json']))
})

test('owned collection is cleaned after success, lost creation replies or failed indexing/search',async()=>{
  const original=globalThis.fetch
  try {
    for(const failure of [null,'create-reply-lost','index-incomplete','search-incomplete','delete-failed','delete-unconfirmed']) {
      let exists=false,deletes=0
      const requests=[]
      const state={status:'green',points_count:1000,indexed_vectors_count:failure==='index-incomplete'?0:1000,segments_count:1,
        config:{params:{vectors:{size:8,distance:'Cosine'}},hnsw_config:{full_scan_threshold:10},optimizer_config:{indexing_threshold:1}}}
      const respond=(result,status=200)=>new Response(JSON.stringify({status:'ok',result}),{status})
      globalThis.fetch=async(url,options={})=>{
        const path=new URL(url).pathname,method=options.method??'GET';requests.push([method,path])
        if(method==='PUT'&&!path.endsWith('/points')){
          exists=true
          if(failure==='create-reply-lost')throw Error('Synthetic lost reply')
          return respond(true)
        }
        if(method==='PUT')return respond({status:'completed'})
        if(method==='DELETE'){
          deletes++
          if(failure==='delete-failed')return respond(null,500)
          if(failure!=='delete-unconfirmed')exists=false
          return respond(true)
        }
        if(method==='POST')return respond(Array.from({length:failure==='search-incomplete'?1:2},(_,id)=>({id})))
        return respond(state,exists?200:404)
      }
      const execute=()=>benchmark({url:'http://127.0.0.1:6333',points:1000,dimensions:8,queries:10,k:2,ef:8,seed:1,indexWaitMs:0})
      if(failure===null)assert.equal((await execute()).syntheticCollectionRemoved,true)
      else await assert.rejects(execute)
      assert.equal(deletes,1,failure)
      const names=new Set(requests.map(([,path])=>path.split('/')[2]))
      assert.equal(names.size,1);assert.match([...names][0],/^test_ann_[a-f0-9]{32}$/)
      if(!['delete-failed','delete-unconfirmed'].includes(failure))assert.equal(exists,false)
    }
  } finally {globalThis.fetch=original}
})
test('synthetic vectors are reproducible and ANN recall is measured against exact IDs',()=>{
  assert.deepEqual(vectorGenerator(1,8)(),vectorGenerator(1,8)())
  const result=summarize([{exactIds:['1','2'],annIds:['2','3'],exactMs:10,annMs:4},{exactIds:['1','2'],annIds:['1','2'],exactMs:20,annMs:8}],2)
  assert.equal(result.recallAtK,.75);assert.equal(result.exactLatencyMs.p95,20)
  assert.throws(()=>summarize([],2))
  assert.throws(()=>summarize([{exactIds:['1','1'],annIds:['1','2'],exactMs:1,annMs:1}],2))
})
