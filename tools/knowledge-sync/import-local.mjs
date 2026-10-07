import {readFileSync,statSync} from 'node:fs';
import {validateLocalPacket,importPendingPacket} from './lib/local-import.mjs';

// Local operator packet only. No login, automatic approval, database replacement or source fetch.
try {
  const args=process.argv.slice(2);
  const path=args.find(v=>!v.startsWith('--'));
  if (!path || args.some(v=>v.startsWith('--') && v!=='--apply') || args.filter(v=>!v.startsWith('--')).length!==1)
    throw new Error('USAGE: node import-local.mjs packet.json [--apply]');
  if (statSync(path).size > 4*1024*1024) throw new Error('PACKET_TOO_LARGE');
  const text=new TextDecoder('utf-8',{fatal:true}).decode(readFileSync(path));
  const packet=JSON.parse(text);
  const valid=validateLocalPacket(packet);
  if (!args.includes('--apply')) console.log(JSON.stringify({status:'VALIDATED_NOT_IMPORTED',sources:valid.metadata.sources.length}));
  else console.log(JSON.stringify(await importPendingPacket(packet,{
    base:process.env.KNOWLEDGE_IMPORT_BASE_URL,token:process.env.KNOWLEDGE_ADMIN_TOKEN})));
} catch(error) {
  // Do not echo malformed packets, provider bodies or credentials.
  const safe=/^[A-Z0-9_ :.[\]-]+$/.test(error.message) ? error.message : 'LOCAL_IMPORT_FAILED';
  console.error(safe);process.exitCode=1;
}
