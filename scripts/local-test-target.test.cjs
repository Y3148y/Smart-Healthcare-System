const {test}=require('node:test'),assert=require('node:assert/strict')
const {localTestTarget}=require('./local-test-target.cjs')
test('all mutating probe entry points fail before browser dependencies or networking',()=>{
  const {spawnSync}=require('node:child_process'),path=require('node:path')
  for(const name of ['test-admin-ui.cjs','test-catalog-ui.cjs','test-human-review-ui.cjs',
    'test-patient-profile-ui.cjs','test-knowledge-approval-ui.cjs','test-booking-confirmation-ui.cjs','test-current-intent-live.cjs','test-progress-live.cjs',
    'test-triage-audit-live.cjs','test-booking-input-live.cjs']){
    const result=spawnSync(process.execPath,[path.join(__dirname,name)],{encoding:'utf8',timeout:5000})
    assert.notEqual(result.status,0,name)
    assert.match(result.stderr,/Live probe disabled/,name)
    assert.doesNotMatch(result.stderr,/Cannot find module/,name)
  }
})
test('mutating probes require explicit apply and an explicit target',()=>{
  assert.throws(()=>localTestTarget('http://127.0.0.1:5188',[]),/--apply/)
  assert.throws(()=>localTestTarget(undefined,['--apply']),/explicit/)
})
test('only clean loopback HTTP origins are accepted',()=>{
  assert.equal(localTestTarget('http://127.0.0.1:8082/',['--apply']),'http://127.0.0.1:8082')
  assert.equal(localTestTarget('http://[::1]:8082',['--apply']),'http://[::1]:8082')
  for(const url of ['https://example.com','http://example.com','http://localhost.evil.test',
    'http://user:pass@localhost','http://localhost/api','http://localhost/?key=x','http://localhost/#x', 'file:///tmp']){
    assert.throws(()=>localTestTarget(url,['--apply']),/loopback/)
  }
})
