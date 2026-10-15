/** Persist only request identity and a digest, never the patient's question text. */
export async function turnIdentity(sessionId:string,content:string):Promise<string> {
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(content.trim()))))
    .map(v=>v.toString(16).padStart(2,'0')).join('')
  const key=`ai-hospital-turn:${sessionId}`
  try {
    const previous=JSON.parse(sessionStorage.getItem(key)||'null')
    if(previous?.hash===hash && typeof previous.requestId==='string' && /^[0-9a-f-]{36}$/.test(previous.requestId))return previous.requestId
  } catch {}
  const requestId=crypto.randomUUID()
  try{sessionStorage.setItem(key,JSON.stringify({hash,requestId}))}catch{}
  return requestId
}
export function clearTurnIdentity(sessionId:string){try{sessionStorage.removeItem(`ai-hospital-turn:${sessionId}`)}catch{}}
