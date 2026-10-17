import {readFileSync,writeFileSync,mkdirSync,realpathSync,existsSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';

export function parseRuntimeList(text) {
  if (typeof text !== 'string' || !text.includes('The following files have been resolved:'))
    throw new Error('Maven resolved runtime list required');
  const children=[];
  for (const raw of text.split(/\r?\n/)) {
    const line=raw.trim();
    if (!line || line==='The following files have been resolved:') continue;
    const fields=line.split(' -- module ')[0].split(':');
    if (![5,6].includes(fields.length)) throw new Error('Invalid resolved coordinate');
    children.push({groupId:fields[0],artifactId:fields[1],version:fields.at(-2),scope:fields.at(-1)});
  }
  return {children};
}

export function runtimePackages(tree) {
  if (!Array.isArray(tree?.children) || !tree.children.length) throw new Error('Resolved runtime dependency tree required');
  const packages = new Map(); let count = 0;
  function visit(node) {
    if (++count > 2000 || !node || !['compile','runtime'].includes(node.scope)
        || ![node.groupId,node.artifactId,node.version].every(value => typeof value === 'string' && /^[A-Za-z0-9_.+\-]+$/.test(value)))
      throw new Error('Invalid runtime dependency coordinate or scope');
    const name = node.groupId + ':' + node.artifactId;
    packages.set(name + '@' + node.version, {name,version:node.version});
    if (node.children !== undefined && !Array.isArray(node.children)) throw new Error('Invalid dependency children');
    for (const child of node.children ?? []) visit(child);
  }
  tree.children.forEach(visit);
  return [...packages.values()].sort((a,b) => (a.name+'@'+a.version).localeCompare(b.name+'@'+b.version));
}

export async function queryAudit(tree, query) {
  const packages = runtimePackages(tree), findings = packages.map(pkg => ({...pkg,advisories:[]}));
  let pending = packages.map((pkg,index) => ({index,query:{package:{ecosystem:'Maven',name:pkg.name},version:pkg.version}}));
  for (let page = 0; pending.length; page++) {
    if (page >= 10) throw new Error('Advisory pagination incomplete');
    const response = await query({queries:pending.map(item => item.query)});
    if (!Array.isArray(response?.results) || response.results.length !== pending.length) throw new Error('Incomplete advisory response');
    const next = [];
    response.results.forEach((result,index) => {
      if (!result || result.vulns !== undefined && !Array.isArray(result.vulns)) throw new Error('Invalid advisory response');
      const target = findings[pending[index].index];
      for (const vuln of result.vulns ?? []) {
        if (typeof vuln?.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9-]{2,120}$/.test(vuln.id)) throw new Error('Invalid advisory identity');
        if (!target.advisories.includes(vuln.id)) target.advisories.push(vuln.id);
      }
      if (result.next_page_token !== undefined) {
        if (typeof result.next_page_token !== 'string' || !result.next_page_token || result.next_page_token.length > 4096)
          throw new Error('Invalid advisory pagination');
        next.push({index:pending[index].index,query:{...pending[index].query,page_token:result.next_page_token}});
      }
    });
    pending = next;
  }
  findings.forEach(item => item.advisories.sort());
  const matched = findings.filter(item => item.advisories.length);
  return {schemaVersion:1,source:'https://api.osv.dev/v1/querybatch',auditedAt:new Date().toISOString(),
    dependencyInventorySha256:createHash('sha256').update(JSON.stringify(tree)).digest('hex'),
    status:matched.length ? 'ADVISORY_MATCHES_FOUND' : 'NO_KNOWN_MATCHES',packages:findings,
    summary:{runtimePackages:packages.length,matchedPackages:matched.length,
      uniqueAdvisories:new Set(matched.flatMap(item => item.advisories)).size},
    boundary:'OSV version matches, not exploitability or a security certification. Excludes test/build plugins, base image and operating-system packages. Network or incomplete responses are failures, never zero findings.'};
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length !== 3 || args[2] !== '--online') throw new Error('Usage: node scripts/audit-maven-runtime.mjs resolved-runtime.txt reports/security/new-audit.json --online');
  const root = realpathSync(resolve(dirname(fileURLToPath(import.meta.url)),'..'));
  const reportsRoot=resolve(root,'reports');
  mkdirSync(reportsRoot,{recursive:true});
  if (realpathSync(reportsRoot)!==reportsRoot) throw new Error('Reports root must not be redirected');
  const reportDirectory = resolve(root,'reports/security');
  mkdirSync(reportDirectory,{recursive:true});
  if (realpathSync(reportDirectory) !== reportDirectory) throw new Error('Security report directory must not be redirected');
  const destination = resolve(root,args[1]);
  if (dirname(destination) !== reportDirectory || !destination.endsWith('.json')) throw new Error('New audit must remain directly under reports/security');
  if (existsSync(destination)) throw new Error('Audit output already exists');
  const inventory = readFileSync(args[0],'utf8');
  const tree = parseRuntimeList(inventory);
  const result = await queryAudit(tree,async body => {
    const response = await fetch('https://api.osv.dev/v1/querybatch',{method:'POST',redirect:'error',
      signal:AbortSignal.timeout(30000),headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    if (!response.ok) { await response.body?.cancel(); throw new Error('Advisory service unavailable'); }
    const reader=response.body.getReader(),parts=[];let size=0;
    try { for (;;) { const next=await reader.read();if(next.done)break;
      size+=next.value.length;if(size>2097152)throw new Error('Advisory response exceeds budget');parts.push(Buffer.from(next.value));
    } } finally { await reader.cancel(); }
    return JSON.parse(Buffer.concat(parts).toString('utf8'));
  });
  result.manifestSha256=createHash('sha256').update(inventory).digest('hex');
  result.manifestFormat='MAVEN_RESOLVED_RUNTIME_LIST';
  writeFileSync(destination,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({status:result.status,summary:result.summary,output:'reports/security/'+destination.split(/[\\/]/).at(-1)}));
  process.exitCode=result.summary.matchedPackages ? 1 : 0;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main().catch(() => { console.error('Runtime dependency audit failed; no clean result is available.');process.exitCode=2; });
