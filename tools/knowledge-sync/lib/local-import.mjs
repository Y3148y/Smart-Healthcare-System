import {createHash} from 'node:crypto';

export function validateLocalPacket(packet) {
  if (!packet || packet.schemaVersion !== 1 || typeof packet.title !== 'string' || !packet.title.trim()
      || packet.title.length > 240 || typeof packet.body !== 'string' || !packet.body.trim()
      || packet.body.length > 500000) throw new Error('INVALID_LOCAL_PACKET');
  const hash=createHash('sha256').update(packet.body,'utf8').digest('hex');
  if (packet.contentSha256 !== hash) throw new Error('BODY_HASH_MISMATCH');
  const m=packet.metadata;
  if (!m || m.schemaVersion !== 1 || !['source_extract','reviewed_summary'].includes(m.contentKind)
      || typeof m.language !== 'string' || !m.language.trim()
      || !Array.isArray(m.sources) || !m.sources.length || m.sources.length > 8
      || !['pending','restricted','permitted'].includes(m.permissionStatus)) throw new Error('INVALID_METADATA');
  if (m.permissionStatus === 'permitted' && (typeof m.permissionEvidence !== 'string' || !m.permissionEvidence.trim()))
    throw new Error('PERMISSION_EVIDENCE_REQUIRED');
  for (const source of m.sources) {
    let url;
    try {url=new URL(source.url);} catch {throw new Error('INVALID_SOURCE');}
    if (!['https:','http:'].includes(url.protocol) || url.username || url.password
        || typeof source.sourceId !== 'string' || !source.sourceId.trim()
        || typeof source.publisher !== 'string' || !source.publisher.trim()
        || (source.rawSha256 != null && !/^[a-f0-9]{64}$/.test(source.rawSha256))
        || (source.fetchedAt != null && !Number.isFinite(Date.parse(source.fetchedAt)))) throw new Error('INVALID_SOURCE');
  }
  for (const name of ['topics','population','exclusions','prerequisites','evidenceUses']) {
    if (!Array.isArray(m[name]) || m[name].length > 32 || m[name].some(v => typeof v !== 'string' || !v.trim() || v.length > 240))
      throw new Error('INVALID_SCOPE');
  }
  if (!m.evidenceUses.length || m.evidenceUses.some(v=>!['general_information','direction_reference','warning_reference'].includes(v)))
    throw new Error('INVALID_EVIDENCE_USE');
  return {title:packet.title,body:packet.body,metadata:m};
}

export function localApiBase(value='http://127.0.0.1:8081/api/') {
  const url=new URL(value);
  if (!['http:','https:'].includes(url.protocol) || !['127.0.0.1','localhost','[::1]'].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || url.pathname !== '/api/')
    throw new Error('LOCAL_BACKEND_REQUIRED');
  return url;
}

export async function importPendingPacket(packet,{base,token,fetcher=fetch}) {
  const body=validateLocalPacket(packet);
  const url=localApiBase(base);
  if (typeof token !== 'string' || !token || /\s/.test(token)) throw new Error('ADMIN_TOKEN_REQUIRED');
  const response=await fetcher(new URL('admin/knowledge/documents',url),{
    method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},
    body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(15000)});
  if (!response.ok) throw new Error('IMPORT_HTTP_'+response.status);
  const document=await response.json();
  if (!document || typeof document.id !== 'string' || document.status !== 'PENDING_REVIEW')
    throw new Error('UNEXPECTED_IMPORT_STATUS');
  return {id:document.id,status:document.status};
}
