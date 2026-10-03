const base = '/api'
const readStoredToken = () => { try { return localStorage.getItem('ai-hospital-token') || '' } catch { return '' } }
let token = readStoredToken()
let demoRefresh:Promise<boolean>|null=null
// Keep the in-memory token usable when embedded browsers block localStorage.
export const setToken = (value:string) => { token=value; try { localStorage.setItem('ai-hospital-token', value) } catch {} }
async function refreshDemoToken():Promise<boolean> {
  const params=new URLSearchParams(window.location.search)
  const demo=params.get('demo')
  const login=(params.get('login')||'').trim().toLowerCase()
  if(!demo && !login)return false
  const username=demo==='admin'||login==='admin'?'admin':login==='lisi'?'lisi':'zhangsan'
  if(!demoRefresh)demoRefresh=(async()=>{
    try{
      const response=await fetch(`${base}/auth/login`,{method:'POST',headers:{'Content-Type':'application/json; charset=UTF-8'},body:JSON.stringify({username,password:'123456'})})
      if(!response.ok)return false
      const data=await response.json()
      if(!data?.token)return false
      setToken(data.token)
      return true
    }catch{return false}
  })().finally(()=>{demoRefresh=null})
  return demoRefresh
}
async function authenticatedResponse(path:string, init:RequestInit = {}):Promise<Response> {
  const headers = new Headers(init.headers)
  // Explicit UTF-8 prevents Chinese symptom descriptions being decoded with a legacy code page.
  if (!headers.has('Content-Type') && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json; charset=UTF-8')
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  const requestToken = token
  let response = await fetch(base + path, { ...init, headers })
  if (response.status === 401 && path !== '/auth/login') {
    // Another request may already have refreshed the token before this 401 arrived.
    if (token !== requestToken || await refreshDemoToken()) {
      headers.set('Authorization', `Bearer ${token}`)
      response = await fetch(base + path, { ...init, headers })
    }
  }
  if (!response.ok) {
    const details=await response.json().catch(()=>({message:''}))
    throw new Error(details.message||`HTTP ${response.status}${response.status===401?'：登录已失效':''}`)
  }
  return response
}
export async function api<T>(path:string, init:RequestInit = {}):Promise<T> {
  return (await authenticatedResponse(path,init)).json() as Promise<T>
}

export async function streamResult<T>(path:string, body:unknown, onStage:(stage:string)=>void):Promise<T> {
  const response=await authenticatedResponse(path,{method:'POST',headers:{Accept:'text/event-stream'},body:JSON.stringify(body)})
  if(!response.headers.get('Content-Type')?.includes('text/event-stream')||!response.body)throw new Error('状态连接不可用，请刷新会话核对')
  const reader=response.body.getReader(),decoder=new TextDecoder('utf-8')
  let buffer='',result:T|undefined,received=false
  function consume(){
    buffer=buffer.replace(/\r\n/g,'\n')
    let end:number
    while((end=buffer.indexOf('\n\n'))>=0){
      const frame=buffer.slice(0,end);buffer=buffer.slice(end+2)
      const lines=frame.split('\n'),event=lines.find(l=>l.startsWith('event:'))?.slice(6).trim()
      const data=lines.filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n')
      if(!data)continue
      const value=JSON.parse(data)
      if(event==='failure')throw new Error(value.message||'处理未完成，请刷新会话核对')
      if(event==='status')onStage(value.stage)
      if(event==='result'){result=value as T;received=true}
    }
  }
  try{
    while(true){const chunk=await reader.read();if(chunk.done)break;buffer+=decoder.decode(chunk.value,{stream:true});if(buffer.length>2000000)throw new Error('状态数据过大，请刷新会话');consume()}
    buffer+=decoder.decode();consume()
    if(!received)throw new Error('状态连接中断，请刷新会话核对已保存的内容')
    return result as T
  }catch(error){await reader.cancel().catch(()=>{});throw error}finally{reader.releaseLock()}
}
