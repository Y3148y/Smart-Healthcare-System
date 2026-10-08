export async function patientResources<T extends Record<string,()=>Promise<unknown>>>(loaders:T) {
  const names=Object.keys(loaders) as (keyof T)[]
  const outcomes=await Promise.allSettled(names.map(name=>loaders[name]()))
  return names.map((name,index)=>({name,outcome:outcomes[index]}))
}
