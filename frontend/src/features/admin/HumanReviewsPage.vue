<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../api'
import type { HumanReview, HumanReviewQueueItem } from '../triage/types'
type Summary={request:HumanReview;sessionTitle:string;patientStatement:string;riskLevel:string;suggestedDepartment:string|null;safetyTip:string|null;assessmentVersion:number|null;assessedAt:string|null;source:string}
type QueueItem=HumanReviewQueueItem
const reviews=ref<QueueItem[]>([]),summary=ref<Summary|null>(null),loading=ref(false),busy=ref(false),error=ref(''),notice=ref('')
const label=(s:string)=>({PENDING:'等待处理',ACCEPTED:'已受理',CLOSED:'已关闭'}[s]||s)
async function load(){loading.value=true;error.value='';summary.value=null;try{reviews.value=await api<QueueItem[]>('/admin/human-reviews')}catch(e:any){error.value=e.message}finally{loading.value=false}}
async function view(id:string){busy.value=true;error.value='';summary.value=null;try{summary.value=await api<Summary>(`/admin/human-reviews/${id}/summary`)}catch(e:any){error.value=e.message}finally{busy.value=false}}
async function change(id:string,status:string){busy.value=true;error.value='';notice.value='';try{const updated=await api<QueueItem>(`/admin/human-reviews/${id}`,{method:'PATCH',body:JSON.stringify({status})});reviews.value=reviews.value.map(r=>r.id===id?updated:r);if(summary.value?.request.id===id)summary.value.request={...summary.value.request,status:updated.status};notice.value='申请状态已更新'}catch(e:any){error.value=e.message}finally{busy.value=false}}
onMounted(load)
</script>
<template>
  <section><div class="admin-title"><div><h2>人工导诊申请</h2><p>只查看申请关联会话的必要摘要，非实时医疗服务；尚未连接医院值守与通知。</p></div><button class="mini" :disabled="loading||busy" @click="load">刷新申请</button></div>
    <p v-if="loading" role="status">正在加载申请…</p><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div class="table-card"><table><thead><tr><th>提交时间</th><th>申请编号</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="r in reviews" :key="r.id"><td>{{ new Date(r.createdAt).toLocaleString() }}</td><td>{{ r.id }}</td><td>{{ label(r.status) }}</td><td><button class="mini" :disabled="busy||!r.summaryAccessible" @click="view(r.id)">{{ r.summaryAccessible?'查看摘要':'摘要需授权' }}</button> <button v-if="r.status==='PENDING'" class="mini" :disabled="busy" @click="change(r.id,'ACCEPTED')">受理</button> <button v-if="r.status!=='CLOSED'" class="mini" :disabled="busy" @click="change(r.id,'CLOSED')">关闭</button></td></tr>
      <tr v-if="!loading && !error && !reviews.length"><td colspan="4" class="empty">暂无人工导诊申请</td></tr>
    </tbody></table></div>
    <article v-if="summary" class="review-summary" aria-labelledby="summary-heading"><h3 id="summary-heading">申请关联会话摘要</h3><p>{{ summary.sessionTitle }}</p><dl><dt>最近患者自述（最多600字）</dt><dd>{{ summary.patientStatement || '暂无自述消息' }}</dd><dt>系统分诊状态</dt><dd>{{ summary.riskLevel }} · {{ summary.suggestedDepartment || '未生成科室建议' }}<span v-if="summary.assessmentVersion"> · 第{{ summary.assessmentVersion }}版</span></dd><dt v-if="summary.safetyTip">安全提示</dt><dd v-if="summary.safetyTip">{{ summary.safetyTip }}</dd></dl><small>自述与系统分诊，仅供导诊核对，不是医生病历。摘要为查看时的当前版本，不是申请时快照。</small></article>
  </section>
</template>
<style scoped>.review-summary{margin-top:24px;padding:24px;background:#eef6ff;border-radius:12px}.review-summary dd{margin:8px 0 18px;white-space:pre-wrap;overflow-wrap:anywhere}.review-summary dt{font-weight:600}.review-summary small{color:#61768d}button:focus-visible{outline:3px solid #91c5ff;outline-offset:2px}</style>
