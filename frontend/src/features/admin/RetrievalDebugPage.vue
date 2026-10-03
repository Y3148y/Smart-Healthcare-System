<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../../api'
import { label, score, sources } from './types'
import type { RetrievalReport } from './types'
const query = ref(''), submitted = ref(''), report = ref<RetrievalReport | null>(null), loading = ref(false), error = ref('')
async function search() {
  if (loading.value || !query.value.trim()) return
  loading.value = true; error.value = ''; report.value = null; submitted.value = query.value.trim()
  try { report.value = await api<RetrievalReport>(`/admin/knowledge/search/details?q=${encodeURIComponent(submitted.value)}`) }
  catch (e: any) { error.value = e.message || '检索请求失败' }
  finally { loading.value = false }
}
</script>
<template>
  <div class="admin-workspace"><div class="admin-title"><div><h2>检索调试</h2><p>查看真实候选及淘汰过程。此操作只检索资料，不调用问诊 LLM，不生成诊断或预约。</p></div></div><form class="searchbar" @submit.prevent="search"><input v-model="query" aria-label="检索问题" maxlength="2000" required placeholder="输入需要复现的问题"><button class="primary" :disabled="loading || !query.trim()" type="submit">{{ loading?'检索中…':'执行检索' }}</button></form><p v-if="error" class="admin-error" role="alert">{{ error }}</p><p v-if="loading" role="status">正在召回候选并按当前配置重排…</p>
    <template v-if="report"><p>本次查询：{{ submitted }}</p><div class="admin-status-grid"><article><small>实际执行路径</small><b>{{ report.mode }}</b></article><article><small>向量检索</small><b>{{ label(report.semanticStatus) }}</b></article><article><small>重排服务</small><b>{{ label(report.rerankStatus) }}</b></article><article><small>总耗时 / 保留片段</small><b>{{ report.elapsedMs }}ms / {{ report.retrieval.evidence.length }}</b></article></div><p class="admin-notice">BM25、向量相似度、RRF 和重排分数量纲不同，均不代表医学可信概率。命中片段不证明回答中的每句话都有依据。</p>
    <h3>最终保留片段</h3><p v-if="!report.retrieval.evidence.length" class="admin-empty">本次没有保留片段；下方可查看候选及阻断原因。</p><article v-for="(e,i) in report.retrieval.evidence" :key="i" class="table-card admin-segment"><b>{{ i+1 }}. {{ e.title }}</b><p>{{ e.excerpt }}</p><div class="admin-source-links"><a v-for="url in sources(e.source)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a></div></article>
    <h3>召回与融合候选（{{ report.candidates.length }}）</h3><div class="table-card admin-scroll"><table class="admin-candidate-table"><thead><tr><th>资料 / 片段</th><th>BM25 排名 / 分数</th><th>向量排名 / 分数</th><th>RRF 分数</th><th>重排分数</th><th>去留原因</th></tr></thead><tbody><tr v-for="(c,i) in report.candidates" :key="i"><td><b>{{ c.title }}</b><details><summary>片段与来源</summary><p>{{ c.excerpt }}</p><small>{{ c.source }}</small></details></td><td>{{ c.lexicalRank || '—' }} / {{ score(c.lexicalScore) }}</td><td>{{ c.semanticRank || '—' }} / {{ score(c.semanticScore) }}</td><td>{{ score(c.fusedScore) }}</td><td>{{ score(c.rerankScore) }}</td><td><span class="admin-badge" :class="{ok:c.kept}">{{ c.kept?'保留':'淘汰' }}</span><small class="admin-cell-note">{{ label(c.reason) }}</small></td></tr><tr v-if="!report.candidates.length"><td colspan="6" class="empty">没有召回候选</td></tr></tbody></table></div></template>
    <p v-else-if="!loading && !error" class="admin-empty">输入问题后，可逐项检查两路召回、融合和重排结果。</p>
  </div>
</template>
