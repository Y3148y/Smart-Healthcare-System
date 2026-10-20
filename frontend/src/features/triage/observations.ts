export interface Observation {
  symptom:string;bodySite:string|null;onset:string|null;duration:string|null;trigger:string|null;severity:string|null;
  subject:string;temporality:string;assertion:string;sourceRefs:{messageId:string;quote:string}[]
}
export interface ObservationSnapshot {
  sessionId:string;stateVersion:number;status:string;clinicalReview:string;observations:Observation[];
  unresolvedConflicts:{field:string;sourceRefs:{messageId:string;quote:string}[]}[]
  timeline?:{id:string;observation:Observation;corrects:string|null;state:string}[]
}
export function isObservationSnapshot(value:unknown,sessionId:string):value is ObservationSnapshot {
  if(!value||typeof value!=='object')return false
  const data=value as ObservationSnapshot
  const refs=(items:unknown)=>Array.isArray(items)&&items.length>=1&&items.length<=5&&items.every(source=>
    source&&typeof source.messageId==='string'&&typeof source.quote==='string')
  return data.sessionId===sessionId&&Number.isSafeInteger(data.stateVersion)&&data.stateVersion>=0
    &&data.status===(data.stateVersion===0?'NO_COMPLETED_SNAPSHOT':'COMPLETED_OBSERVATIONS')&&data.clinicalReview==='NOT_REVIEWED'
    &&Array.isArray(data.observations)&&data.observations.length<=500&&data.observations.every(event=>
      event&&typeof event.symptom==='string'&&typeof event.subject==='string'&&typeof event.temporality==='string'
      &&typeof event.assertion==='string'&&refs(event.sourceRefs)
      &&['bodySite','onset','duration','trigger','severity'].every(key=>{const field=event[key as keyof Observation];return field==null||typeof field==='string'}))
    &&Array.isArray(data.unresolvedConflicts)&&data.unresolvedConflicts.length<=100
    &&(data.timeline===undefined||Array.isArray(data.timeline)&&data.timeline.length<=500
      &&new Set(data.timeline.map(e=>e?.id)).size===data.timeline.length
      &&data.timeline.every(e=>e&&/^[a-f0-9]{64}$/.test(e.id)&&['OBSERVED','SUPERSEDED','AMBIGUOUS_CORRECTION'].includes(e.state)
        &&(e.corrects===null||/^[a-f0-9]{64}$/.test(e.corrects))&&e.observation&&typeof e.observation.symptom==='string'))
    &&data.unresolvedConflicts.every(conflict=>conflict&&typeof conflict.field==='string'&&refs(conflict.sourceRefs))
}
export function observationLabels(event:Observation){
  const subject=({SELF:'原标记：本人',OTHER:'原标记：他人',UNKNOWN:'主体不明'} as Record<string,string>)[event.subject]||'主体未记录'
  const time=({CURRENT:'当轮标记为当前',PAST:'当轮标记为既往',UNKNOWN:'时间不明'} as Record<string,string>)[event.temporality]||'时间未记录'
  const assertion=({AFFIRMED:'当轮肯定',NEGATED:'当轮否认',UNCERTAIN:'当轮不确定'} as Record<string,string>)[event.assertion]||'肯否未记录'
  return [subject,time,assertion].join(' · ')
}
export function observationEmptyMessage(snapshot:ObservationSnapshot){
  return snapshot.stateVersion===0||snapshot.status==='NO_COMPLETED_SNAPSHOT'
    ?'尚无已完成的AI观察记录；不表示没有症状。'
    :'该版本未保存症状观察；不表示没有症状。'
}
export function observationConflictLabel(field:string){
  return ({SYMPTOM:'症状',BODY_SITE:'部位',ONSET:'开始时间',DURATION:'持续时间',TRIGGER:'诱因',SEVERITY:'程度',
    SUBJECT:'主体',TEMPORALITY:'时间归属',ASSERTION:'肯否表达'} as Record<string,string>)[field]||'未标记字段'
}
