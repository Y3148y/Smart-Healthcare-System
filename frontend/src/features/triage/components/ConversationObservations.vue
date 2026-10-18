<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {api} from '../../../api'
import {selectionVersion} from '../selectionVersion'
import {observationLabels,observationEmptyMessage,isObservationSnapshot,observationConflictLabel} from '../observations'
import type {ObservationSnapshot} from '../observations'
const props=defineProps<{sessionId:string}>()
const snapshot=ref<ObservationSnapshot|null>(null),loading=ref(false),error=ref(''),visible=ref(20)
const selection=selectionVersion(),details=ref<HTMLDetailsElement|null>(null)
function clear(){selection.next();snapshot.value=null;loading.value=false;error.value='';visible.value=20;if(details.value)details.value.open=false}
watch(()=>props.sessionId,clear)
onBeforeUnmount(clear)
async function load(){
  const version=selection.next(),id=props.sessionId
  loading.value=true;error.value='';snapshot.value=null
  try{
    const value=await api<ObservationSnapshot>(`/triage/sessions/${encodeURIComponent(id)}/observations`)
    if(!isObservationSnapshot(value,id))throw new Error('Invalid observation response')
    if(selection.isCurrent(version)&&props.sessionId===id)snapshot.value=value
  }catch{
    if(selection.isCurrent(version))error.value='观察记录暂不可用，可稍后重试；这不表示没有症状。'
  }finally{if(selection.isCurrent(version))loading.value=false}
}
function toggle(event:Event){
  if((event.currentTarget as HTMLDetailsElement).open){if(!snapshot.value&&!loading.value)void load()}
  else{selection.next();loading.value=false}
}
</script>
<template>
  <details ref="details" class="observation-records" @toggle.self="toggle">
    <summary>会话观察记录（非病历）</summary>
    <div class="observation-body" aria-live="polite">
      <p>以下是已完成轮次的历史AI抽取记录，未作医学确认，不代表目前仍有这些症状。展开不会调用模型；窗口外观察不会自动外发。</p>
      <p v-if="loading">正在读取…</p>
      <p v-else-if="error" role="status">{{ error }} <button type="button" class="text-btn" @click="load">重试</button></p>
      <template v-else-if="snapshot">
        <p>保存版本 {{ snapshot.stateVersion }}<span v-if="snapshot.unresolvedConflicts.length"> · {{ snapshot.unresolvedConflicts.length }} 处待核对冲突</span></p>
        <p v-if="!snapshot.observations.length">{{ observationEmptyMessage(snapshot) }}</p>
        <ol v-else><li v-for="(event,i) in snapshot.observations.slice(0,visible)" :key="i">
          <b>{{ event.symptom }}</b><small>{{ observationLabels(event) }}</small>
          <p v-if="event.bodySite">部位原词：{{ event.bodySite }}</p><p v-if="event.duration">持续时间原词：{{ event.duration }}</p>
          <p v-for="(source,j) in event.sourceRefs" :key="j">原话：{{ source.quote }}</p>
        </li></ol>
        <button v-if="snapshot.observations.length>visible" type="button" class="text-btn" @click="visible+=20">显示更多（已显示 {{ visible }} / {{ snapshot.observations.length }}）</button>
        <details v-if="snapshot.unresolvedConflicts.length"><summary>查看待核对的冲突原话</summary>
          <ul><li v-for="(conflict,i) in snapshot.unresolvedConflicts" :key="i">{{ observationConflictLabel(conflict.field) }}
            <p v-for="(source,j) in conflict.sourceRefs" :key="j">原话：{{ source.quote }}</p>
          </li></ul>
        </details>
      </template>
    </div>
  </details>
</template>
<style scoped>
.observation-records{margin:0 16px 8px;color:#617286;font-size:13px}
summary{cursor:pointer;padding:5px 0}
.observation-body{max-height:min(14rem,32vh);overflow:auto;padding:8px 12px;background:#f7f9fc;border-radius:8px}
li{margin:10px 0}small{display:block;color:#788596}p{margin:5px 0}
</style>
