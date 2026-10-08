import type {ResponseProvenance} from './types'

export function responseDuration(localSeconds:unknown, provenance?:ResponseProvenance|null):{label:string,seconds:number}|null {
  if(typeof localSeconds==='number' && Number.isFinite(localSeconds) && localSeconds>=0)
    return {label:'本次请求用时',seconds:localSeconds}
  const elapsed=provenance?.turnTrace?.elapsedMs
  if(typeof elapsed==='number' && Number.isFinite(elapsed) && elapsed>=0)
    return {label:'后台本轮处理用时',seconds:elapsed/1000}
  // Model-only elapsedMs excludes retrieval and is not a request/turn duration.
  return null
}
