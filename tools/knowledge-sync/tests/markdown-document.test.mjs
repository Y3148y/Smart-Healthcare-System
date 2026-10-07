import test from 'node:test';
import assert from 'node:assert/strict';
import {parseMarkdownDocument,inspectMarkdownDocument} from '../lib/markdown-document.mjs';
import {createHash} from 'node:crypto';
const header={schemaVersion:1,documentId:'fixture',title:'Engineering fixture',approvalStatus:'PENDING_REVIEW',
  clinicalReviewStatus:'NOT_REVIEWED',metadata:{schemaVersion:1,language:'en',contentKind:'source_extract',
    sources:[{sourceId:'fixture',publisher:'Test',url:'https://example.org'}],topics:['fixture'],population:[],
    exclusions:[],prerequisites:[],evidenceUses:['general_information'],permissionStatus:'pending',permissionEvidence:null}};
const document=h=>'---\n'+JSON.stringify(h)+'\n---\n# Fixture\n\n## Section\nSynthetic body.\n';
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
