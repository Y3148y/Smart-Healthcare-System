const {test}=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),{transformSync}=require('esbuild')
const file=path.resolve(__dirname,'../src/features/triage/observations.ts'),mod=new Module(file,module)
mod._compile(transformSync(fs.readFileSync(file,'utf8'),{loader:'ts',format:'cjs'}).code,file)
test('historical CURRENT does not get presented as a current medical fact',()=>{
  const label=mod.exports.observationLabels({subject:'SELF',temporality:'CURRENT',assertion:'AFFIRMED'})
  assert.ok(label.includes('当轮标记为当前'));assert.ok(label.includes('当轮肯定'))
  assert.ok(mod.exports.observationLabels({}).includes('未记录'))
})
test('missing or empty observations never assert that the patient has no symptoms',()=>{
  for(const snapshot of [{stateVersion:0,status:'NO_COMPLETED_SNAPSHOT'},{stateVersion:3,status:'COMPLETED_OBSERVATIONS'}])
    assert.ok(mod.exports.observationEmptyMessage(snapshot).includes('不表示没有症状'))
})
test('owner view is lazy, read-only, invalidates old requests and never renders quote HTML',()=>{
  const source=fs.readFileSync(path.resolve(__dirname,'../src/features/triage/components/ConversationObservations.vue'),'utf8')
  assert.ok(source.includes('/observations`'));assert.ok(source.includes('encodeURIComponent(id)'))
  assert.ok(source.includes('selection.isCurrent(version)'));assert.ok(source.includes('onBeforeUnmount(clear)'))
  assert.ok(source.includes('@toggle.self="toggle"'));assert.ok(!source.includes('onMounted'))
  assert.ok(!source.includes('v-html'));assert.ok(!source.includes('localStorage'));assert.ok(source.includes('32vh'))
})
test('wrong-session, malformed and contradictory snapshot responses are rejected',()=>{
  const valid={sessionId:'one',stateVersion:0,status:'NO_COMPLETED_SNAPSHOT',clinicalReview:'NOT_REVIEWED',observations:[],unresolvedConflicts:[]}
  assert.equal(mod.exports.isObservationSnapshot(valid,'one'),true)
  assert.equal(mod.exports.isObservationSnapshot(valid,'two'),false)
  for(const changed of [{observations:null},{stateVersion:2},{clinicalReview:'APPROVED'},
    {observations:[{symptom:'fixture',sourceRefs:null}]}])
    assert.equal(mod.exports.isObservationSnapshot({...valid,...changed},'one'),false)
})
