import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync,unlinkSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {runtimePackages,queryAudit,parseRuntimeList} from './audit-maven-runtime.mjs';
const dependency = (artifactId='alpha') => ({groupId:'org.synthetic',artifactId,version:'1.0.0',scope:'compile'});
const tree = () => ({children:[{...dependency(),children:[dependency('beta')]},dependency()]});

test('CLI refuses missing consent, escaped output and overwrite before any network request',()=>{
  const directory=mkdtempSync(join(tmpdir(),'runtime-audit-'));
  const existing=join(directory,'existing.json');
  mkdirSync('reports/security',{recursive:true});
  const protectedOutput=join('reports/security',`synthetic-${randomUUID()}.json`);
  writeFileSync(existing,'unchanged');
  writeFileSync(protectedOutput,'unchanged');
  try {
    for(const args of [[],['missing.txt','README.md','--online'],['missing.txt',existing,'--online'],
      ['missing.txt',protectedOutput,'--online']]) {
      const result=spawnSync(process.execPath,['scripts/audit-maven-runtime.mjs',...args],{encoding:'utf8',timeout:5000});
      assert.equal(result.status,2);assert.equal(result.error,undefined);
      assert.match(result.stderr,/no clean result/);
    }
    assert.equal(readFileSync(existing,'utf8'),'unchanged');
    assert.equal(readFileSync(protectedOutput,'utf8'),'unchanged');
  } finally {unlinkSync(protectedOutput);rmSync(directory,{recursive:true});}
});
test('resolved dependency list preserves classifiers and module annotations without guessing jar filenames',()=>{
  const list='The following files have been resolved:\n  org.synthetic:alpha:jar:1.0:compile -- module synthetic [auto]\n  org.synthetic:beta:jar:linux:2.0:runtime\n';
  assert.deepEqual(runtimePackages(parseRuntimeList(list)),[{name:'org.synthetic:alpha',version:'1.0'},{name:'org.synthetic:beta',version:'2.0'}]);
  for(const invalid of ['none','The following files have been resolved:\nfailed',
    'The following files have been resolved:\norg.synthetic:alpha:jar:1.0:test'])
    assert.throws(()=>runtimePackages(parseRuntimeList(invalid)));
});
test('runtime manifest is deduplicated, bounded and excludes non-runtime scopes by rejecting wrong input',()=>{
  assert.equal(runtimePackages(tree()).length,2);
  for(const invalid of [{}, {children:[]}, {children:[{...dependency(),scope:'test'}]},
    {children:[{...dependency(),version:'value with spaces'}]}]) assert.throws(()=>runtimePackages(invalid));
});
test('pagination remains tied to the correct package and duplicate advisories cannot inflate counts',async()=>{
  let calls=0;
  const result=await queryAudit(tree(),async request=>{
    if(calls++===0) return {results:[{vulns:[{id:'GHSA-synthetic-one'}],next_page_token:'next'},{vulns:[]}]};
    assert.equal(request.queries.length,1);assert.equal(request.queries[0].package.name,'org.synthetic:alpha');
    return {results:[{vulns:[{id:'GHSA-synthetic-one'},{id:'CVE-2099-99999'}]}]};
  });
  assert.equal(result.status,'ADVISORY_MATCHES_FOUND');assert.equal(result.summary.matchedPackages,1);
  assert.equal(result.summary.uniqueAdvisories,2);
});
test('missing results, malformed IDs, network failures and endless pagination never become zero findings',async()=>{
  for(const response of [{results:[]},{results:[{vulns:'bad'},{}]}, {results:[{vulns:[{id:'../secret'}]},{}]}])
    await assert.rejects(queryAudit(tree(),async()=>response));
  await assert.rejects(queryAudit(tree(),async()=>{throw new Error('synthetic network outage')}));
  await assert.rejects(queryAudit({children:[dependency()]},async()=>({results:[{next_page_token:'loop'}]})),/incomplete/);
});
test('complete empty matches are reported as no known matches, not proof of security',async()=>{
  const result=await queryAudit(tree(),async()=>({results:[{},{}]}));
  assert.equal(result.status,'NO_KNOWN_MATCHES');assert.equal(result.summary.matchedPackages,0);
  assert.match(result.boundary,/not exploitability/);
});
