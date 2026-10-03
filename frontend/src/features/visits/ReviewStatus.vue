<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../api'
import type { HumanReview } from '../triage/types'
const reviews=ref<HumanReview[]>([]),loading=ref(false),error=ref('')
const label=(s:string)=>({PENDING:'等待处理',ACCEPTED:'已受理',CLOSED:'已关闭'}[s]||s)
async function load(){loading.value=true;error.value='';try{reviews.value=await api<HumanReview[]>('/patient/human-reviews')}catch(e:any){error.value=e.message}finally{loading.value=false}}
onMounted(load)
</script>
<template>
  <section aria-labelledby="review-heading"><h2 id="review-heading">我的人工导诊申请</h2>
    <p>申请仅用于非实时导诊处理，不代表医生诊疗；紧急情况请直接线下求助。</p>
    <button type="button" class="mini" :disabled="loading" @click="load">{{ loading?'正在刷新…':'刷新申请状态' }}</button>
    <p v-if="error" role="alert">申请加载失败：{{ error }}</p>
    <div class="table-card"><table><thead><tr><th>申请时间</th><th>申请原因</th><th>处理状态</th></tr></thead><tbody>
      <tr v-for="r in reviews" :key="r.id"><td>{{ new Date(r.createdAt).toLocaleString() }}</td><td>{{ r.reason }}</td><td>{{ label(r.status) }}</td></tr>
      <tr v-if="!loading && !error && !reviews.length"><td colspan="3" class="empty">尚无申请，可在预问诊会话中申请人工导诊。</td></tr>
    </tbody></table></div>
  </section>
</template>
