/** Persisted server state survives browser reload; disconnect does not cancel a turn. */
export function conversationProcessing(status:string|undefined, localRunning:boolean):boolean {
  return localRunning || status==='处理中'
}

export function conversationSendBlocked(status:string|undefined, localRunning:boolean):boolean {
  return conversationProcessing(status,localRunning) || status==='紧急提示'
}
