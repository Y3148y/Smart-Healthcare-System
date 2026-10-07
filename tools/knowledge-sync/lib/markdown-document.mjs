import {createHash} from 'node:crypto';
import {validateLocalPacket} from './local-import.mjs';

// JSON is used inside the front matter deliberately: no implicit YAML dates or types.
export function parseMarkdownDocument(text,provenanceText) {
  const normalized=text.replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
  const match=/^---\n([\s\S]*?)\n---\n([\s\S]+)$/.exec(normalized);
  if(!match) throw new Error('DOCUMENT_FRONT_MATTER_REQUIRED');
  let header;
  try {header=JSON.parse(match[1]);} catch {throw new Error('INVALID_DOCUMENT_FRONT_MATTER');}
  if(header.schemaVersion!==1 || typeof header.documentId!=='string' || !header.documentId.trim()
      || header.approvalStatus!=='PENDING_REVIEW' || header.clinicalReviewStatus!=='NOT_REVIEWED')
    throw new Error('INVALID_DOCUMENT_REVIEW_STATE');
  const body=match[2];
  let metadata=header.metadata;
  if(header.provenanceFile!==undefined) {
    if(header.provenanceFile!=='provenance.json' || typeof provenanceText!=='string'
        || createHash('sha256').update(provenanceText).digest('hex')!==header.provenanceSha256)
      throw new Error('PROVENANCE_HASH_MISMATCH');
    let audit;
    try {audit=JSON.parse(provenanceText);} catch {throw new Error('INVALID_PROVENANCE');}
    if(audit.documentId!==header.documentId || audit.approvalStatus!==header.approvalStatus
        || audit.clinicalReviewStatus!==header.clinicalReviewStatus) throw new Error('INVALID_PROVENANCE');
    if(header.metadata!==undefined) throw new Error('DUPLICATE_DOCUMENT_METADATA');
    metadata=audit.metadata;
  }
  const packet={schemaVersion:1,title:header.title,body,
    contentSha256:createHash('sha256').update(body).digest('hex'),metadata};
  validateLocalPacket(packet);
  return packet;
}

export function inspectMarkdownDocument(text,sourceText,provenanceText,additionalSources={}) {
  const packet=parseMarkdownDocument(text,provenanceText);
  const normalized=text.replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
  const header=JSON.parse(/^---\n([\s\S]*?)\n---\n/.exec(normalized)[1]);
  // Existing extracted snapshots were saved as packet.body plus one trailing LF.
  const source=sourceText.replace(/\r\n/g,'\n');
  const hash=value=>createHash('sha256').update(value).digest('hex');
  let scopeReviewStatus=header.scopeReviewStatus??'UNKNOWN';
  if(header.provenanceFile!==undefined) {
    if(header.provenanceFile!=='provenance.json' || typeof provenanceText!=='string'
        || hash(provenanceText)!==header.provenanceSha256) throw new Error('PROVENANCE_HASH_MISMATCH');
    let audit;
    try {audit=JSON.parse(provenanceText);} catch {throw new Error('INVALID_PROVENANCE');}
    if(audit.documentId!==header.documentId || !Array.isArray(audit.sourceSnapshots) || !audit.sourceSnapshots.length)
      throw new Error('INVALID_PROVENANCE');
    scopeReviewStatus=audit.scopeReviewStatus??'UNKNOWN';
    const ids=new Set(),names=new Set();
    for(const snapshot of audit.sourceSnapshots) {
      if(!/^source(?:\.[a-z]+)?\.en\.md$/.test(snapshot.file??'') || names.has(snapshot.file)
          || ids.has(snapshot.sourceId) || !/^[a-f0-9]{64}$/.test(snapshot.sha256??'')) throw new Error('INVALID_PROVENANCE');
      names.add(snapshot.file);ids.add(snapshot.sourceId);
      const value=snapshot.file==='source.en.md'?source:additionalSources[snapshot.file]?.replace(/\r\n/g,'\n');
      if(typeof value!=='string' || ![hash(value),hash(value.replace(/\n$/,''))].includes(snapshot.sha256))
        throw new Error('SOURCE_SNAPSHOT_HASH_MISMATCH');
    }
    if(!names.has('source.en.md') || packet.metadata.sources.some(s=>!ids.has(s.sourceId)))
      throw new Error('SOURCE_PROVENANCE_INCOMPLETE');
  } else {
    if(header.sourceSnapshot!=='source.en.md' || !/^[a-f0-9]{64}$/.test(header.sourceExtractSha256??''))
      throw new Error('SOURCE_SNAPSHOT_REQUIRED');
    if(![hash(source),hash(source.replace(/\n$/,''))].includes(header.sourceExtractSha256))
      throw new Error('SOURCE_SNAPSHOT_HASH_MISMATCH');
  }
  return {documentId:header.documentId,bodyCharacters:packet.body.length,contentSha256:packet.contentSha256,
    status:header.approvalStatus,sourceVerified:true,scopeReviewStatus};
}
