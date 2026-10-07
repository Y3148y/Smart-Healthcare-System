import {createHash} from 'node:crypto';
import {validateLocalPacket} from './local-import.mjs';

// JSON is used inside the front matter deliberately: no implicit YAML dates or types.
export function parseMarkdownDocument(text) {
  const normalized=text.replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
  const match=/^---\n([\s\S]*?)\n---\n([\s\S]+)$/.exec(normalized);
  if(!match) throw new Error('DOCUMENT_FRONT_MATTER_REQUIRED');
  let header;
  try {header=JSON.parse(match[1]);} catch {throw new Error('INVALID_DOCUMENT_FRONT_MATTER');}
  if(header.schemaVersion!==1 || typeof header.documentId!=='string' || !header.documentId.trim()
      || header.approvalStatus!=='PENDING_REVIEW' || header.clinicalReviewStatus!=='NOT_REVIEWED')
    throw new Error('INVALID_DOCUMENT_REVIEW_STATE');
  const body=match[2];
  const packet={schemaVersion:1,title:header.title,body,
    contentSha256:createHash('sha256').update(body).digest('hex'),metadata:header.metadata};
  validateLocalPacket(packet);
  return packet;
}

export function inspectMarkdownDocument(text,sourceText) {
  const packet=parseMarkdownDocument(text);
  const normalized=text.replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
  const header=JSON.parse(/^---\n([\s\S]*?)\n---\n/.exec(normalized)[1]);
  if(header.sourceSnapshot!=='source.en.md' || !/^[a-f0-9]{64}$/.test(header.sourceExtractSha256??''))
    throw new Error('SOURCE_SNAPSHOT_REQUIRED');
  // Existing extracted snapshots were saved as packet.body plus one trailing LF.
  const source=sourceText.replace(/\r\n/g,'\n');
  const hash=value=>createHash('sha256').update(value).digest('hex');
  if(![hash(source),hash(source.replace(/\n$/,''))].includes(header.sourceExtractSha256))
    throw new Error('SOURCE_SNAPSHOT_HASH_MISMATCH');
  return {documentId:header.documentId,bodyCharacters:packet.body.length,contentSha256:packet.contentSha256,
    status:header.approvalStatus,sourceVerified:true,scopeReviewStatus:header.scopeReviewStatus??'UNKNOWN'};
}
