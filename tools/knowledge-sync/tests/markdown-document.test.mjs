import test from 'node:test';
import assert from 'node:assert/strict';
import {parseMarkdownDocument,inspectMarkdownDocument} from '../lib/markdown-document.mjs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const header={schemaVersion:1,documentId:'fixture',title:'Engineering fixture',approvalStatus:'PENDING_REVIEW',
  clinicalReviewStatus:'NOT_REVIEWED',metadata:{schemaVersion:1,language:'en',contentKind:'source_extract',
    sources:[{sourceId:'fixture',publisher:'Test',url:'https://example.org'}],topics:['fixture'],population:[],
    exclusions:[],prerequisites:[],evidenceUses:['general_information'],permissionStatus:'pending',permissionEvidence:null}};
const document=h=>'---\n'+JSON.stringify(h)+'\n---\n# Fixture\n\n## Section\nSynthetic body.\n';
test('corpus validation is a portable read-only command covering every public document',()=>{
  const cli=fileURLToPath(new URL('../validate-document.mjs',import.meta.url));
  const checked=spawnSync(process.execPath,[cli,'--corpus'],{encoding:'utf8'});
  assert.equal(checked.status,0,checked.stderr);
  assert.deepEqual(JSON.parse(checked.stdout),{documents:11,allSourceVerified:true,reviewStatuses:['PENDING_REVIEW']});
  const missing=spawnSync(process.execPath,[cli,'does-not-exist.md'],{encoding:'utf8'});
  assert.equal(missing.status,1);
  assert.ok(!missing.stderr.includes('does-not-exist.md'));
});
test('front matter is validated but excluded from the indexed body',()=>{
  const p=parseMarkdownDocument(document(header));
  assert.equal(p.body,'# Fixture\n\n## Section\nSynthetic body.\n');
  assert.equal(p.metadata.sources[0].sourceId,'fixture');
  assert.equal(parseMarkdownDocument(document(header).replace(/\n/g,'\r\n')).contentSha256,p.contentSha256);
});
test('missing metadata or self-approved revisions fail closed',()=>{
  assert.throws(()=>parseMarkdownDocument('# Body'),/FRONT_MATTER/);
  assert.throws(()=>parseMarkdownDocument(document({...header,approvalStatus:'READY'})),/REVIEW_STATE/);
  assert.throws(()=>parseMarkdownDocument(document({...header,metadata:null})),/METADATA/);
});
test('source snapshots are verified separately from body and cannot silently drift',()=>{
  const source='# Source\nFixture';
  const h={...header,sourceSnapshot:'source.en.md',sourceExtractSha256:createHash('sha256').update(source).digest('hex')};
  assert.equal(inspectMarkdownDocument(document(h),source+'\n').sourceVerified,true);
  assert.throws(()=>inspectMarkdownDocument(document(h),source+'changed'),/HASH_MISMATCH/);
});
test('lean front matter resolves one bound provenance record without embedding audit prose',()=>{
  const source='# Source\nFixture',sha=x=>createHash('sha256').update(x).digest('hex');
  const audit={...header,sourceSnapshots:[{sourceId:'fixture',file:'source.en.md',sha256:sha(source)}],
    scopeReviewStatus:'PENDING',editorNotes:'Not medical evidence'};
  const provenance=JSON.stringify(audit);
  const {metadata,...lean}=header;
  lean.provenanceFile='provenance.json';lean.provenanceSha256=sha(provenance);
  const p=parseMarkdownDocument(document(lean),provenance);
  assert.equal(p.metadata.sources[0].sourceId,'fixture');
  assert.equal(p.metadata.intakeAudit.documentId,'fixture');
  assert.equal(p.metadata.intakeAudit.provenanceSha256,sha(provenance));
  assert.equal(p.metadata.intakeAudit.contentSha256,p.contentSha256);
  assert.equal(p.metadata.intakeAudit.sourceSnapshots[0].sha256,sha(source));
  assert.ok(!p.body.includes('Not medical evidence'));
  assert.equal(inspectMarkdownDocument(document(lean),source,provenance).sourceVerified,true);
  assert.throws(()=>parseMarkdownDocument(document(lean),provenance+' '),/HASH/);
  assert.throws(()=>parseMarkdownDocument(document({...lean,metadata}),provenance),/DUPLICATE/);
});
test('all sources are bound and source filenames cannot traverse directories',()=>{
  const source='# Source\nFixture',sha=x=>createHash('sha256').update(x).digest('hex');
  const {metadata,...lean}=header;
  const check=(snapshots,meta=metadata)=>{
    const provenance=JSON.stringify({...header,metadata:meta,sourceSnapshots:snapshots});
    return ()=>inspectMarkdownDocument(document({...lean,provenanceFile:'provenance.json',provenanceSha256:sha(provenance)}),source,provenance);
  };
  assert.throws(check([{sourceId:'fixture',file:'../source.en.md',sha256:sha(source)}]),/PROVENANCE|INTAKE_AUDIT/);
  assert.throws(check([{sourceId:'other',file:'source.en.md',sha256:sha(source)}]),/INCOMPLETE|INTAKE_AUDIT/);
});
