// Only the newest explicit selection may update the visible conversation.
export function selectionVersion(){
  let current=0
  return {next:()=>++current,isCurrent:(version:number)=>version===current}
}
