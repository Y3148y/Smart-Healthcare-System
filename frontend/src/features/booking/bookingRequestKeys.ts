// Per-page retry identity; no patient text or credential is stored.
// A reload is not an automatic retry: patients should check their saved receipts first.
export function bookingRequestKeys(create:()=>string) {
  const pending=new Map<string,string>()
  const identity=(doctorId:string,date:string,period:string,sessionId:string)=>JSON.stringify([doctorId,date,period,sessionId])
  return {
    get(doctorId:string,date:string,period:string,sessionId:string){
      const id=identity(doctorId,date,period,sessionId)
      let key=pending.get(id)
      if(!key){key=create();pending.set(id,key)}
      return key
    },
    complete(doctorId:string,date:string,period:string,sessionId:string){pending.delete(identity(doctorId,date,period,sessionId))}
  }
}
