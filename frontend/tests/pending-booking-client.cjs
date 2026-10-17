const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module')
const {transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/booking/pendingBookingClient.ts')
const mod=new Module(file,module);mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
const {pendingBookingClient}=mod.exports
const doctor={id:'synthetic',name:'Fixture',department:'Test',date:'2026-10-20',period:'AM',fee:0}
test('uncertain confirmation reuses the same action and sends no mutable doctor parameters',async()=>{
  const calls=[];let failures=1,keys=0
  const client=pendingBookingClient(async(url,options)=>{
    calls.push({url,body:options.body&&JSON.parse(options.body)})
    if(url.endsWith('/actions'))return {actionId:'server-bound',doctor}
    if(failures-->0)throw Error('Synthetic response lost')
    return {id:'receipt'}
  },()=>`key-${++keys}`,()=>true)
  await assert.rejects(client.book(doctor,'walk-in'))
  assert.deepEqual(await client.book(doctor,'walk-in'),{id:'receipt'})
  assert.equal(keys,1);assert.equal(calls.filter(c=>c.url.endsWith('/actions')).length,1)
  for(const call of calls.filter(c=>c.url.endsWith('/confirm')))
    assert.deepEqual(call,{url:'/appointments/actions/server-bound/confirm',body:{confirmed:true}})
})
test('cancel never confirms; successful booking and explicit logout clear pending identity',async()=>{
  const calls=[];let accepted=false,keys=0
  const client=pendingBookingClient(async(url,options)=>{calls.push([url,options.method]);return url.endsWith('/actions')?{actionId:'bound',doctor}:{id:'receipt'}},()=>`key-${++keys}`,()=>accepted)
  assert.equal(await client.book(doctor,'walk-in'),null)
  assert.deepEqual(calls.at(-1),['/appointments/actions/bound','DELETE'])
  assert.equal(calls.some(([url])=>url.endsWith('/confirm')),false)
  accepted=true;await client.book(doctor,'walk-in');await client.book(doctor,'walk-in')
  assert.equal(keys,3)
  client.clear()
})
test('different doctors, dates, periods and sessions retain separate server proposals',async()=>{
  let keys=0;const prepared=[]
  const client=pendingBookingClient(async(url,options)=>{
    if(url.endsWith('/actions')){prepared.push(JSON.parse(options.body));return {actionId:`bound-${keys}`,doctor}}
    throw Error('Synthetic unconfirmed response')
  },()=>`key-${++keys}`,()=>true)
  for(const [d,s] of [[doctor,'s'],[{...doctor,id:'other'},'s'],[{...doctor,date:'next'},'s'],
    [{...doctor,period:'PM'},'s'],[doctor,'other']])await assert.rejects(client.book(d,s))
  assert.equal(keys,5);assert.equal(new Set(prepared.map(p=>p.requestKey)).size,5)
  client.clear();await assert.rejects(client.book(doctor,'s'));assert.equal(keys,6)
})
test('uncertain preparation preserves its original key on an explicit retry',async()=>{
  const keys=[];let failed=true
  const client=pendingBookingClient(async(url,options)=>{
    if(url.endsWith('/actions')){keys.push(JSON.parse(options.body).requestKey);if(failed){failed=false;throw Error('Synthetic preparation response lost')}return {actionId:'bound',doctor}}
    return {id:'receipt'}
  },()=>String(keys.length+1),()=>true)
  await assert.rejects(client.book(doctor,'walk-in'));await client.book(doctor,'walk-in')
  assert.deepEqual(keys,['1','1'])
})
test('failed cancellation never confirms or keeps a cancelled action as the next proposal',async()=>{
  let accepted=false,keys=0,confirmed=0
  const client=pendingBookingClient(async(url,options)=>{
    if(url.endsWith('/actions'))return {actionId:`bound-${keys}`,doctor}
    if(options.method==='DELETE')throw Error('Synthetic cancel response lost')
    confirmed++;return {id:'receipt'}
  },()=>String(++keys),()=>accepted)
  await assert.rejects(client.book(doctor,'walk-in'));assert.equal(confirmed,0)
  accepted=true;await client.book(doctor,'walk-in');assert.equal(keys,2);assert.equal(confirmed,1)
})
test('definite stale or expired rejection requires a fresh user-driven preparation, not silent retry',async()=>{
  let keys=0,prepares=0,conflict=true
  const client=pendingBookingClient(async(url)=>{
    if(url.endsWith('/actions')){prepares++;return {actionId:`bound-${keys}`,doctor}}
    if(conflict){conflict=false;throw Object.assign(Error('Synthetic expired action'),{status:409})}
    return {id:'receipt'}
  },()=>String(++keys),()=>true)
  await assert.rejects(client.book(doctor,'walk-in'))
  assert.equal(prepares,1)
  await client.book(doctor,'walk-in');assert.equal(prepares,2);assert.equal(keys,2)
})
