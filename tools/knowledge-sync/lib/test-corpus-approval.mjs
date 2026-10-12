import {readMarkdownFile} from './markdown-file.mjs';
import {importPendingPacket,localApiBase} from './local-import.mjs';

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
  if(packet?.metadata?.permissionStatus!=='pending' || !packet.metadata.sources?.length)
    throw new Error('PENDING_CORPUS_PACKET_REQUIRED');
  const imported=await importPendingPacket(packet,{base:url.href,token,fetcher});
  const id=encodeURIComponent(imported.id);
  const detail=await jsonRequest(new URL('admin/knowledge/'+id+'/details',url),requestOptions(token,'GET'),fetcher);
  const declared=await jsonRequest(new URL('admin/knowledge/'+id+'/metadata',url),requestOptions(token,'GET'),fetcher);
  if(detail?.document?.status==='READY') {
    if(declared.metadata?.permissionEvidence!==TEST_EVIDENCE)
      throw new Error('EXISTING_APPROVAL_NOT_TEST_ONLY');
  } else {
    if(detail?.document?.status!=='PENDING_REVIEW' || declared.metadata?.permissionStatus!=='pending')
      throw new Error('UNEXPECTED_APPROVAL_STATE');
    const metadata={...packet.metadata,permissionStatus:'permitted',permissionEvidence:TEST_EVIDENCE};
    await jsonRequest(new URL('admin/knowledge/'+id+'/metadata',url),requestOptions(token,'PUT',metadata),fetcher);
    const approved=await jsonRequest(new URL('admin/knowledge/'+id+'/approve',url),requestOptions(token,'POST'),fetcher);
    if(approved?.status!=='READY') throw new Error('APPROVAL_NOT_READY');
  }
  const chunks=await jsonRequest(new URL('admin/knowledge/'+id+'/chunks',url),requestOptions(token,'GET'),fetcher);
  if(!Array.isArray(chunks) || chunks.length===0) throw new Error('APPROVED_DOCUMENT_HAS_NO_CHUNKS');
  return {title:packet.title,status:'READY',chunks:chunks.length};
}

export function readTestCorpus(paths) {
  if(!Array.isArray(paths) || paths.length!==11) throw new Error('EXPECTED_ELEVEN_DOCUMENTS');
  const packets=paths.map(path=>readMarkdownFile(path).packet);
  if(new Set(packets.map(packet=>packet.title)).size!==11)
    throw new Error('DUPLICATE_CORPUS_DOCUMENT');
  return packets;
}

export {TEST_EVIDENCE};
