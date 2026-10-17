export function invocationOriginLabel(origin?:string|null):string {
  return ({WORKFLOW:'后端流程选择',AGENT:'Agent 选择（后端执行）',ADMIN:'管理员试运行',
    MCP:'管理端 MCP 子集入口'} as Record<string,string>)[origin||'']||'发起方未记录'
}
