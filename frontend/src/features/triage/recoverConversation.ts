import type { Conversation } from './types'

// Recover the accepted turn, never replay the POST: disconnect does not cancel server work.
export async function recoverConversation(
  load:()=>Promise<Conversation>, previousUserCount:number, content:string,
  sleep:(ms:number)=>Promise<void>=ms=>new Promise(resolve=>setTimeout(resolve,ms)),
  attempts=20,
):Promise<{conversation:Conversation|null,saved:boolean,processing:boolean}> {
  let conversation:Conversation|null=null, saved=false
  for(let i=0;i<attempts;i++){
    try{
      conversation=await load()
      const users=conversation.messages.filter(message=>message.role==='USER')
      saved=users.length>previousUserCount && users[users.length-1]?.content===content
      if(!saved || conversation.session.status!=='处理中')break
    }catch{ /* A transient read failure must not trigger a duplicate write. */ }
    if(i+1<attempts)await sleep(1500)
  }
  return {conversation,saved,processing:saved && conversation?.session.status==='处理中'}
}
