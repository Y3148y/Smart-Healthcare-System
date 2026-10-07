<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import { label, sources, time } from './types'
import type { DocumentDetail, KnowledgeDocument, KnowledgeRuntime } from './types'
import KnowledgeImportForm from './KnowledgeImportForm.vue'
import KnowledgeProvenancePanel from './KnowledgeProvenancePanel.vue'
const documents = ref<KnowledgeDocument[]>([]), runtime = ref<KnowledgeRuntime>({}), detail = ref<DocumentDetail | null>(null)
const loading = ref(false), busy = ref(false), detailLoading = ref(false), error = ref(''), notice = ref('')
const filter = ref('ALL'), keyword = ref(''), editor = ref(false)
const filtered = computed(() => documents.value.filter(d => (filter.value === 'ALL' || d.status === filter.value) && d.title.includes(keyword.value.trim())))
async function load() {
  loading.value = true; error.value = ''
  try { [documents.value, runtime.value] = await Promise.all([api<KnowledgeDocument[]>('/admin/knowledge'), api<KnowledgeRuntime>('/admin/knowledge/runtime')]) }
  catch (e: any) { error.value = e.message || '知识库加载失败' }
  finally { loading.value = false }
}
async function view(id: string) {
  detailLoading.value = true; detail.value = null; error.value = ''
  try { detail.value = await api<DocumentDetail>(`/admin/knowledge/${encodeURIComponent(id)}/details`) }
  catch (e: any) { error.value = e.message || '详情加载失败' }
  finally { detailLoading.value = false }
}
async function saved(document: KnowledgeDocument) {
  editor.value = false
  await load(); await view(document.id)
  notice.value = '正文与来源已保存，资料待审批，尚未用于患者检索。'
}
async function approve(id: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await api(`/admin/knowledge/${encodeURIComponent(id)}/approve`, { method: 'POST' })
    await load(); await view(id); notice.value = '审批完成。向量写入是否成功请查看下方索引状态。'
  } catch (e: any) { error.value = e.message || '审批失败' }
  finally { busy.value = false }
}
async function sync() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api<{ status: string; success: string }>('/admin/knowledge/index/sync', { method: 'POST' })
    notice.value = result.success === 'true' ? '已完成增量索引同步。' : `未完成向量同步：${label(result.status)}`
    const id = detail.value?.document.id; await load(); if (id) await view(id)
  } catch (e: any) { error.value = e.message || '索引同步失败' }
  finally { busy.value = false }
}
async function withdraw(id: string) {
  if (busy.value || !window.confirm('撤回后资料保留，但后续检索不再使用。确认撤回到待审批？')) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await api(`/admin/knowledge/${encodeURIComponent(id)}/withdraw`, { method: 'POST' })
    await load(); await view(id); notice.value = '资料已撤回到待审批。已有回答和正在处理的请求不会被追溯删除。'
  } catch (e: any) { error.value = e.message || '撤回失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <div class="admin-workspace">
    <div class="admin-title"><div><h2>医学知识库</h2><p>正文、来源声明和审批状态保存到数据库；审批与向量同步状态分别展示。</p></div><div class="admin-actions"><button class="mini" :disabled="loading || busy" @click="load">刷新</button><button class="mini" :disabled="busy" @click="sync">{{ busy ? '处理中…' : '同步向量索引' }}</button><button class="primary" @click="editor = !editor">{{ editor ? '收起录入' : '新增资料' }}</button></div></div>
    <p v-if="error" class="admin-error" role="alert">{{ error }}</p><p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
    <KnowledgeImportForm v-if="editor" @saved="saved" />
    <div class="admin-toolbar"><label>标题<input v-model="keyword" placeholder="筛选资料标题"></label><label>审批状态<select v-model="filter"><option value="ALL">全部</option><option value="READY">已审批</option><option value="PENDING_REVIEW">待审批</option></select></label><span>{{ filtered.length }} 份资料 · 最近检索模式：{{ runtime.mode || '未检测' }}</span></div>
    <div class="table-card admin-scroll"><table><thead><tr><th>资料标题</th><th>片段数</th><th>审批状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="d in filtered" :key="d.id"><td><b>{{ d.title }}</b></td><td>{{ d.chunks }}</td><td><span class="admin-badge" :class="{ok:d.status==='READY'}">{{ d.status==='READY'?'已审批':'待审批' }}</span></td><td>{{ time(d.updatedAt) }}</td><td><button class="mini" :disabled="detailLoading" @click="view(d.id)">查看原文与片段</button></td></tr><tr v-if="!filtered.length"><td colspan="5" class="empty">{{ loading?'正在加载…':'没有匹配的资料' }}</td></tr></tbody></table></div>
    <p v-if="detailLoading" role="status">正在加载文档详情…</p>
    <KnowledgeProvenancePanel v-if="detail" :key="detail.document.id + detail.document.updatedAt" :document-id="detail.document.id"
      :pending="detail.document.status === 'PENDING_REVIEW'" @corrected="view(detail.document.id)" />
    <button v-if="detail?.document.status === 'READY'" class="mini" :disabled="busy" @click="withdraw(detail.document.id)">撤回到待审批</button>
    <section v-if="detail" class="table-card admin-document" aria-label="文档详情"><div class="admin-title"><h3>{{ detail.document.title }}</h3><div class="admin-actions"><button v-if="detail.document.status==='PENDING_REVIEW'" class="primary" :disabled="busy" @click="approve(detail.document.id)">核对后审批通过</button><button class="mini" @click="detail=null">关闭详情</button></div></div><p>来源：{{ detail.source }}</p><div class="admin-source-links"><a v-for="url in sources(detail.source)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a></div><p>向量状态：<b>{{ label(detail.indexStatus) }}</b> · 本进程已验证或写入 {{ detail.indexedChunks }}/{{ detail.segments.length }} 个片段</p><small>{{ detail.indexNote }}</small><details><summary>查看完整原文</summary><pre>{{ detail.document.body }}</pre></details><h4>片段预览（{{ detail.segments.length }}）</h4><article v-for="(segment, i) in detail.segments" :key="i" class="admin-segment"><b>片段 {{ i+1 }}</b><p>{{ segment.excerpt }}</p></article></section>
  </div>
</template>
