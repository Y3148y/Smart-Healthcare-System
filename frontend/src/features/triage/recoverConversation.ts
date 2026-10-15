import type { Conversation, Turn } from './types'

// Recover the accepted turn, never replay the POST: disconnect does not cancel server work.
export async function recoverConversation(
  load:()=>Promise<Conversation>, previousUserCount:number, content:string,
  sleep:(ms:number)=>Promise<void>=ms=>new Promise(resolve=>setTimeout(resolve,ms)),
  attempts=20,
  requestId?:string,
  loadTurn?:()=>Promise<Turn>,
):Promise<{conversation:Conversation|null,saved:boolean,processing:boolean,turn:Turn|null}> {
  let conversation:Conversation|null=null, saved=false,processing=false,turn:Turn|null=null
  for(let i=0;i<attempts;i++){
    try{
      conversation=await load()
      const users=conversation.messages.filter(message=>message.role==='USER')
      turn=requestId && conversation.latestTurn?.requestId!==requestId && conversation.latestTurn && loadTurn
        ? await loadTurn() : conversation.latestTurn||null
      saved=requestId ? turn?.requestId===requestId
        && users.some(message=>message.id===turn?.userMessageId)
        : users.length>previousUserCount && users[users.length-1]?.content===content
      processing=saved && (requestId ? turn?.status==='RUNNING' : conversation.session.status==='处理中')
      if(!saved || !processing)break
    }catch{ /* A transient read failure must not trigger a duplicate write. */ }
    if(i+1<attempts)await sleep(1500)
  }
  return {conversation,saved,processing,turn}
}
