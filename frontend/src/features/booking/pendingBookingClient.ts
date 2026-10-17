type Doctor = {id:string; name:string; department:string; date:string; period:string; fee:number}
type Action = {actionId:string; doctor:Doctor; expiresAt:string}
type Request = <T>(path:string, options:{method:string;body?:string})=>Promise<T>

/** Keep the same server action after uncertain confirmation; never silently create another. */
export function pendingBookingClient(request:Request, createKey:()=>string,
  confirm:(action:Action)=>boolean) {
  const pending=new Map<string,{key:string;action?:Action}>()
  function failure(identity:string,error:unknown):never {
    const status=(error as {status?:number})?.status
    // A definite business rejection permits a new explicit preparation, never an automatic retry.
    if(status===409||status===404)pending.delete(identity)
    throw error
  }
  return {
    async book<T>(doctor:Doctor,sessionId:string):Promise<T|null> {
      const identity=JSON.stringify([doctor.id,doctor.date,doctor.period,sessionId])
      let state=pending.get(identity)
      if(!state){state={key:createKey()};pending.set(identity,state)}
      if(!state.action) state.action=await request<Action>('/appointments/actions',{
        method:'POST',body:JSON.stringify({doctorId:doctor.id,sessionId,requestKey:state.key})})
        .catch(error=>failure(identity,error))
      const action=state.action
      if(!confirm(action)) {
        try {await request(`/appointments/actions/${encodeURIComponent(action.actionId)}`,{method:'DELETE'})}
        finally {pending.delete(identity)}
        return null
      }
      const receipt=await request<T>(`/appointments/actions/${encodeURIComponent(action.actionId)}/confirm`,{
        method:'POST',body:JSON.stringify({confirmed:true})}).catch(error=>failure(identity,error))
      pending.delete(identity)
      return receipt
    },
    clear(){pending.clear()}
  }
}
