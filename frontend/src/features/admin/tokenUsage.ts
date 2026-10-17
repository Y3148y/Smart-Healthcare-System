export function tokenUsageLabel(source?:string|null):string {
  return ({PROVIDER_REPORTED:'供应商返回值',NOT_REPORTED:'未取得用量',
    NOT_APPLICABLE:'非模型调用，不计模型用量',LEGACY_RECORDED:'旧记录：来源未核实'} as Record<string,string>)[source||'']
    ||'旧记录：来源未知'
}
