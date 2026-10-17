export interface RuntimeTool {tool:string;elapsedMs:number;success:boolean;errorPresent:boolean}
export interface RuntimeCall {
  id:string;time:string;purpose:string;model:string;inputTokens:number|null;outputTokens:number|null;
  elapsedMs:number;success:boolean;tools:RuntimeTool[];traceId:string|null;tokenUsageSource?:string|null;initiator?:string|null
}
export interface TraceDetailsState {traceId:string|null;calls:RuntimeCall[];loading:boolean;error:string}
export const emptyTraceDetails=():TraceDetailsState=>({traceId:null,calls:[],loading:false,error:''})
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Takes a Vue reactive state or a plain test state; no patient text or local storage. */
export function traceDetailsLoader(state:TraceDetailsState,fetchCalls:(traceId:string)=>Promise<RuntimeCall[]>){
  let revision=0
  return {
    clear(){revision++;Object.assign(state,emptyTraceDetails())},
    async load(traceId:string){
      traceId=traceId.toLowerCase()
      const request=++revision
      Object.assign(state,{traceId,calls:[],loading:true,error:''})
      try{
        if(!UUID.test(traceId))throw new Error('Invalid trace')
        const rows=await fetchCalls(traceId)
        if(request!==revision)return
        if(!Array.isArray(rows)||rows.length>200||new Set(rows.map(row=>row.id)).size!==rows.length
          ||rows.some(row=>row.traceId!==traceId||typeof row.id!=='string'||typeof row.time!=='string'))
          throw new Error('Mismatched trace response')
        state.calls=[...rows].sort((a,b)=>a.time<b.time?-1:a.time>b.time?1:a.id<b.id?-1:a.id>b.id?1:0)
      }catch{
        if(request===revision)state.error='本轮技术记录暂不可用，请重试；不会展示其他轮次的旧结果。'
      }finally{
        if(request===revision)state.loading=false
      }
    }
  }
}
