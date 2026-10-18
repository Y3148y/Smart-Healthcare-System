export interface RuntimeTool {tool:string;elapsedMs:number;success:boolean;errorPresent:boolean}
export interface RuntimeCall {
  id:string;time:string;purpose:string;model:string;inputTokens:number|null;outputTokens:number|null;
  elapsedMs:number;success:boolean;tools:RuntimeTool[];traceId:string|null;tokenUsageSource?:string|null;initiator?:string|null
}
export interface TraceDetailsState {traceId:string|null;calls:RuntimeCall[];loading:boolean;error:string}
export const emptyTraceDetails=():TraceDetailsState=>({traceId:null,calls:[],loading:false,error:''})
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Validate the technical projection before templates/filtering consume nested fields. */
export function isRuntimeCalls(value:unknown):value is RuntimeCall[]{
  if(!Array.isArray(value)||value.length>200)return false
  const count=(n:unknown)=>typeof n==='number'&&Number.isSafeInteger(n)&&n>=0
  const usage=(n:unknown)=>n===null||count(n)
  const rows=value as RuntimeCall[]
  return rows.every(row=>row&&typeof row.id==='string'&&row.id.length>0&&typeof row.time==='string'
    &&typeof row.purpose==='string'&&typeof row.model==='string'&&typeof row.success==='boolean'
    &&count(row.elapsedMs)&&usage(row.inputTokens)&&usage(row.outputTokens)
    &&(row.traceId===null||typeof row.traceId==='string'&&UUID.test(row.traceId))
    &&[row.initiator,row.tokenUsageSource].every(field=>field==null||typeof field==='string')
    &&Array.isArray(row.tools)&&row.tools.length<=200&&row.tools.every(tool=>tool
      &&typeof tool.tool==='string'&&count(tool.elapsedMs)&&typeof tool.success==='boolean'&&typeof tool.errorPresent==='boolean'))
    &&new Set(rows.map(row=>row.id)).size===rows.length
}

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
        if(!isRuntimeCalls(rows)||rows.some(row=>row.traceId!==traceId))
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
