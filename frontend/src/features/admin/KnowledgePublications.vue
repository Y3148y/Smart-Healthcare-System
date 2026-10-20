<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../../api'
const props=defineProps<{documentId?:string}>()
interface Release {id:string;source_revision:number;snapshot_sha256:string;build_status:string;created_at:string}
interface State {state:{pointerVersion:number;activeReleaseId:string|null;mode:string};releases:Release[]}
interface ApprovalEvent {id:string;event_type:string;catalog_revision:number;document_sha256:string}
const data=ref<State|null>(null),busy=ref(false),error=ref(''),notice=ref(''),history=ref<ApprovalEvent[]|null>(null)
watch(()=>props.documentId,()=>{history.value=null})
async function read(){
  const value=await api<State>('/admin/knowledge/releases')
  if(!value?.state||!Number.isSafeInteger(value.state.pointerVersion)||value.state.pointerVersion<0||!Array.isArray(value.releases)
    ||!['LEGACY_UNPUBLISHED','VERSIONED'].includes(value.state.mode)
    ||!(value.state.activeReleaseId===null||typeof value.state.activeReleaseId==='string')
    ||value.releases.some(r=>!r||typeof r.id!=='string'||!['BUILDING','READY','FAILED'].includes(r.build_status)
      ||!Number.isSafeInteger(r.source_revision)||r.source_revision<0))throw Error('发布状态结构异常')
  data.value=value
}
async function refresh(){
  if(busy.value)return
  busy.value=true;error.value='';data.value=null;history.value=null
  try{await read()}catch{error.value='发布状态暂不可核实，请刷新后再操作'}finally{busy.value=false}
}
async function change(action:'publish'|'rollback'|'delete',id?:string){
  if(busy.value||!data.value)return
  const description=action==='publish'?'构建并发布当前已审批语料？首次发布后，新审批资料须再次发布才参与检索。':action==='rollback'?'切换到该历史发布？服务端将重新检查索引和当前授权，已撤回资料不能恢复。':'清理该历史发布快照？活动版本、最近三个完整版本和未满七日版本不能删除。此操作不删除医学正文或审批历史。'
  if(!window.confirm(description))return
  const expectedVersion=data.value.state.pointerVersion
  busy.value=true;error.value='';notice.value='';history.value=null
  try{
    await api('/admin/knowledge/releases'+(id?'/'+encodeURIComponent(id)+(action==='rollback'?'/activate':''):''),
      {method:action==='delete'?'DELETE':'POST',body:JSON.stringify({expectedVersion})})
    notice.value='操作已保存。';data.value=null
    await read()
  }catch{data.value=null;error.value='操作结果待核对，请先刷新发布状态，不要重复提交'}finally{busy.value=false}
}
async function viewHistory(){
  if(busy.value||!props.documentId)return
  const documentId=props.documentId
  busy.value=true;error.value='';history.value=null
  try{
    const value=await api<ApprovalEvent[]>('/admin/knowledge/'+encodeURIComponent(documentId)+'/history')
    if(!Array.isArray(value)||value.some(event=>!event||typeof event.id!=='string'
      ||!['BASELINE','APPROVE','WITHDRAW'].includes(event.event_type)||!Number.isSafeInteger(event.catalog_revision)
      ||event.catalog_revision<0||!/^[a-f0-9]{64}$/.test(event.document_sha256)))throw Error()
    if(documentId===props.documentId)history.value=value
  }catch{if(documentId===props.documentId)error.value='审批历史暂不可读取'}finally{busy.value=false}
}
</script>
<template>
  <details class="table-card"><summary>知识发布版本与审批历史</summary>
    <p>索引验证后切换活动版本；审批不是发布。未首次发布时仍使用旧版审批直读模式。撤回立即排除资料，历史回滚不能恢复撤回授权。</p>
    <button class="mini" :disabled="busy" @click="refresh">读取发布状态</button>
    <button class="mini" :disabled="busy||!data" @click="change('publish')">构建并发布</button>
    <button v-if="documentId" class="mini" :disabled="busy" @click="viewHistory">查看所选资料审批历史</button>
    <p v-if="error" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p>
    <p v-if="data">模式：{{data.state.mode}} · 指针版本：{{data.state.pointerVersion}} · 活动发布：{{data.state.activeReleaseId||'尚未发布'}}</p>
    <div v-if="data" class="admin-scroll"><table><thead><tr><th>发布ID</th><th>状态</th><th>来源版本</th><th>操作</th></tr></thead>
      <tbody><tr v-for="r in data.releases" :key="r.id"><td>{{r.id}}</td><td>{{r.build_status}}</td><td>{{r.source_revision}}</td><td>
        <button class="mini" :disabled="busy||r.build_status!=='READY'||r.id===data.state.activeReleaseId" @click="change('rollback',r.id)">核验并切换</button>
        <button class="mini" :disabled="busy||r.id===data.state.activeReleaseId" @click="change('delete',r.id)">按保留规则清理</button>
      </td></tr></tbody></table></div>
    <ul v-if="history"><li v-for="event in history" :key="event.id">{{event.event_type}} · 目录版本{{event.catalog_revision}} · {{event.document_sha256}}</li></ul>
  </details>
</template>
