import {readMarkdownFile} from './markdown-file.mjs';
import {importPendingPacket,localApiBase,validateLocalPacket} from './local-import.mjs';

const TEST_EVIDENCE='TEST_ONLY_LOCAL: maintainer authorized these example documents for isolated development testing; clinical, translation and scope review remain incomplete.';

function requestOptions(token,method,body) {
  return {method,headers:{Authorization:'Bearer '+token,...(body===undefined?{}:{'Content-Type':'application/json'})},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),redirect:'error',signal:AbortSignal.timeout(15000)};
}

async function jsonRequest(url,options,fetcher) {
  const response=await fetcher(url,options);
  if(!response.ok) throw new Error('TEST_APPROVAL_HTTP_'+response.status);
  return response.json();
}

/** Explicitly scoped to a loopback test backend. Never edits the bound corpus files. */
export async function approveTestDocument(packet,{base,token,fetcher=fetch}) {
  const url=localApiBase(base);
  if(typeof token!=='string' || !token || /\s/.test(token)) throw new Error('ADMIN_TOKEN_REQUIRED');
  validateLocalPacket(packet);
  // Source reuse permission is distinct from clinical/test approval. Restricted
  // material is never promoted; permitted source material still starts pending.
  if(!['pending','permitted'].includes(packet.metadata.permissionStatus))
    throw new Error('PENDING_CORPUS_PACKET_REQUIRED');
  let id;
  let conflictingImport=false;
  try {
    id=(await importPendingPacket(packet,{base:url.href,token,fetcher})).id;
  } catch(error) {
    if(error.message!=='IMPORT_HTTP_409') throw error;
    conflictingImport=true;
    // A prior test import used the same body but omitted its audit manifest.
    // Only an exact, uniquely identified, TEST_ONLY_LOCAL record may be repaired.
    const documents=await jsonRequest(new URL('admin/knowledge',url),requestOptions(token,'GET'),fetcher);
    const matches=Array.isArray(documents)?documents.filter(document=>
      document.title===packet.title && document.body===packet.body):[];
    if(matches.length!==1) throw new Error('EXISTING_TEST_DOCUMENT_NOT_UNIQUE');
    id=matches[0].id;
  }
  id=encodeURIComponent(id);
  const detail=await jsonRequest(new URL('admin/knowledge/'+id+'/details',url),requestOptions(token,'GET'),fetcher);
  const declared=await jsonRequest(new URL('admin/knowledge/'+id+'/metadata',url),requestOptions(token,'GET'),fetcher);
  if(detail?.document?.title!==packet.title || detail.document.body!==packet.body)
    throw new Error('EXISTING_DOCUMENT_CONTENT_MISMATCH');
  const currentAudit=JSON.stringify(declared.metadata?.intakeAudit??null);
  const expectedAudit=JSON.stringify(packet.metadata.intakeAudit??null);
  // A 409 proves that the idempotency key is shared, not that this command
  // owns the pre-existing record. Never promote an unmarked pending document.
  if(conflictingImport && declared.metadata?.permissionEvidence!==TEST_EVIDENCE)
    throw new Error('CONFLICTING_IMPORT_NOT_TEST_ONLY');
  if(detail?.document?.status==='READY') {
    if(declared.metadata?.permissionEvidence!==TEST_EVIDENCE)
      throw new Error('EXISTING_APPROVAL_NOT_TEST_ONLY');
    if(currentAudit!==expectedAudit) {
      const withdrawn=await jsonRequest(new URL('admin/knowledge/'+id+'/withdraw',url),requestOptions(token,'POST'),fetcher);
      if(withdrawn?.status!=='PENDING_REVIEW') throw new Error('TEST_WITHDRAWAL_FAILED');
    }
  } else {
    const unchangedSourcePermission=declared.metadata?.permissionStatus===packet.metadata.permissionStatus
      && declared.metadata?.permissionEvidence===packet.metadata.permissionEvidence;
    if(detail?.document?.status!=='PENDING_REVIEW' || !['pending','permitted'].includes(declared.metadata?.permissionStatus)
        || (declared.metadata.permissionStatus==='permitted'
          && declared.metadata.permissionEvidence!==TEST_EVIDENCE && !unchangedSourcePermission))
      throw new Error('UNEXPECTED_APPROVAL_STATE');
  }
  if(detail.document.status==='PENDING_REVIEW' || currentAudit!==expectedAudit) {
    const metadata={...packet.metadata,permissionStatus:'permitted',permissionEvidence:TEST_EVIDENCE};
    await jsonRequest(new URL('admin/knowledge/'+id+'/metadata',url),requestOptions(token,'PUT',metadata),fetcher);
    const approved=await jsonRequest(new URL('admin/knowledge/'+id+'/approve',url),requestOptions(token,'POST'),fetcher);
    if(approved?.status!=='READY') throw new Error('APPROVAL_NOT_READY');
  }
  const chunks=await jsonRequest(new URL('admin/knowledge/'+id+'/chunks',url),requestOptions(token,'GET'),fetcher);
  if(!Array.isArray(chunks) || chunks.length===0) throw new Error('APPROVED_DOCUMENT_HAS_NO_CHUNKS');
  return {title:packet.title,status:'READY',chunks:chunks.length};
}

export function readTestCorpus(paths,{expectedCount=11}={}) {
  if(!Number.isSafeInteger(expectedCount) || expectedCount<1 || expectedCount>100
      || !Array.isArray(paths) || paths.length!==expectedCount) throw new Error('UNEXPECTED_DOCUMENT_COUNT');
  const packets=paths.map(path=>readMarkdownFile(path).packet);
  if(new Set(packets.map(packet=>packet.title)).size!==expectedCount)
    throw new Error('DUPLICATE_CORPUS_DOCUMENT');
  if(packets.some(packet=>packet.metadata.permissionStatus==='restricted'))
    throw new Error('RESTRICTED_CORPUS_DOCUMENT');
  return packets;
}

/** Explicit files only: no implicit expansion of an operator's new drafts. */
export function parseTestApprovalArgs(args) {
  let apply=false,expectedCount;
  const paths=[];
  for(let i=0;i<args.length;i++) {
    const arg=args[i];
    if(arg==='--apply' && !apply) apply=true;
    else if(arg==='--expect-count' && expectedCount===undefined && /^[1-9]\d{0,2}$/.test(args[i+1]??''))
      expectedCount=Number(args[++i]);
    else if(!arg.startsWith('--') && /\.md$/i.test(arg)) paths.push(arg);
    else throw new Error('INVALID_TEST_APPROVAL_ARGUMENTS');
  }
  if((paths.length>0)!==(expectedCount!==undefined) || (expectedCount!==undefined
      && (expectedCount>100 || paths.length!==expectedCount))) throw new Error('EXPLICIT_DOCUMENT_COUNT_REQUIRED');
  return {apply,paths,expectedCount:expectedCount??11};
}

export {TEST_EVIDENCE};
