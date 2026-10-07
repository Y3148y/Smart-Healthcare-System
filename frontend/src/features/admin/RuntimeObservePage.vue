<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../api'
import { label, time } from './types'
import type { KnowledgeRuntime, RetrievalEvent } from './types'
interface ToolTrace { tool:string; elapsedMs:number; success:boolean; errorPresent:boolean }
interface Call { id:string; time:string; purpose:string; model:string; inputTokens:number|null; outputTokens:number|null; elapsedMs:number; success:boolean; tools:ToolTrace[]; traceId:string|null }
interface ModelRuntime { configured:boolean; mode:string; modelName:string; detail:string }
const calls = ref<Call[]>([]), events = ref<RetrievalEvent[]>([]), model = ref<ModelRuntime | null>(null), knowledge = ref<KnowledgeRuntime>({})
const loading = ref(false), errors = ref<string[]>([]), callsAvailable = ref(false), failureOnly = ref(false), keyword = ref(''), selected = ref<Call | null>(null)
const filtered = computed(() => calls.value.filter(c => (!failureOnly.value || !c.success || c.tools.some(t => !t.success)) && `${c.purpose} ${c.model} ${c.traceId || ''}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const currentPage = ref(1), pageSize = 10
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const pageCalls = computed(() => filtered.value.slice((currentPage.value-1)*pageSize, currentPage.value*pageSize))
watch([keyword, failureOnly], () => { currentPage.value = 1 })
const successful = computed(() => calls.value.filter(c => c.success).length)
const average = computed(() => calls.value.length ? Math.round(calls.value.reduce((n,c) => n + c.elapsedMs, 0) / calls.value.length) : null)
async function load() {
  loading.value = true; errors.value = []; currentPage.value = 1
  const results = await Promise.allSettled([api<Call[]>('/admin/calls'), api<ModelRuntime>('/admin/ai-runtime'), api<KnowledgeRuntime>('/admin/knowledge/runtime'), api<RetrievalEvent[]>('/admin/knowledge/retrieval-events')])
  const [a,b,c,d] = results
  if (a.status === 'fulfilled') { calls.value = a.value; callsAvailable.value = true } else { callsAvailable.value = false; calls.value = []; selected.value = null; errors.value.push(`调用记录：${a.reason.message}`) }
  if (b.status === 'fulfilled') model.value = b.value; else { model.value = null; errors.value.push(`模型配置：${b.reason.message}`) }
  if (c.status === 'fulfilled') knowledge.value = c.value; else { knowledge.value = {}; errors.value.push(`检索配置：${c.reason.message}`) }
  if (d.status === 'fulfilled') events.value = d.value; else { events.value = []; errors.value.push(`检索执行记录：${d.reason.message}`) }
  loading.value = false
}
onMounted(load)
</script>
<template>
  <div class="admin-workspace"><div class="admin-title"><div><h2>AI 运行观测</h2><p>配置状态与执行结果分开展示。点击刷新读取已有记录，不会额外调用模型。</p></div><button class="mini" :disabled="loading" @click="load">{{ loading?'加载中…':'刷新状态与记录' }}</button></div><div v-if="errors.length" class="admin-error" role="alert"><p v-for="e in errors" :key="e">{{ e }}</p></div>
    <div class="admin-status-grid"><article><small>对话模型配置</small><b>{{ model ? model.configured?'配置已加载':'未配置外部模型' : '未检测' }}</b><span>{{ model?.modelName || model?.mode || '—' }}</span></article><article><small>Embedding 配置</small><b>{{ knowledge.embeddingConfigured==='true'?'配置已加载':knowledge.embeddingConfigured==='false'?'未配置':'未检测' }}</b><span>{{ knowledge.embeddingModel || '—' }}</span></article><article><small>最近向量执行状态</small><b>{{ label(knowledge.semanticStatus) }}</b><span>仅代表最近操作结果</span></article><article><small>Rerank 配置</small><b>{{ knowledge.rerankConfigured==='true'?'配置已加载':knowledge.rerankConfigured==='false'?'未配置':'未检测' }}</b><span>{{ knowledge.rerankModel || '—' }}</span></article></div><p class="admin-notice">{{ model?.detail || '模型状态暂未读取。' }} 当前检索路径：{{ knowledge.mode || '未检测' }}。这些配置卡不代表供应商实时健康。</p>
    <div class="metric-grid"><article><b>{{ callsAvailable?calls.length:'—' }}</b><span>已返回调用记录</span></article><article><b>{{ callsAvailable?successful:'—' }}</b><span>记录中成功次数</span></article><article><b>{{ callsAvailable && calls.length ? `${Math.round(successful/calls.length*100)}%` : '—' }}</b><span>记录中成功率</span></article><article><b>{{ callsAvailable && average!==null?`${average}ms`:'—' }}</b><span>记录中平均耗时</span></article></div>
    <p v-if="events[0]?.mode.includes('DEPENDENCY_BLOCKED')" class="admin-error" role="status">最近检索被阻断：向量 {{ label(events[0].semanticStatus) }}；重排 {{ label(events[0].rerankStatus) }}。未发布检索依据。</p>
    <h3>模型与工具调用记录</h3><div class="admin-toolbar"><label>筛选用途或模型<input v-model="keyword" placeholder="输入关键词"></label><label class="admin-check"><input v-model="failureOnly" type="checkbox">仅看失败或工具错误</label><small>仅显示技术摘要，不提供患者身份、工具入参和原始错误。旧用量未知显示“—”。</small></div><div class="table-card admin-scroll"><table><thead><tr><th>调用时间</th><th>用途</th><th>模型 / 状态</th><th>Token（入 / 出）</th><th>耗时</th><th>结果</th><th>详情</th></tr></thead><tbody><tr v-for="c in pageCalls" :key="c.id"><td>{{ time(c.time) }}</td><td>{{ c.purpose }}</td><td>{{ c.model }}</td><td>{{ c.inputTokens ?? '—' }} / {{ c.outputTokens ?? '—' }}</td><td>{{ c.elapsedMs }}ms</td><td><span class="admin-badge" :class="{ok:c.success}">{{ c.success?'成功':'失败' }}</span></td><td><button class="mini" @click="selected=c">查看调用顺序</button></td></tr><tr v-if="!filtered.length"><td colspan="7" class="empty">{{ loading?'正在读取…':callsAvailable?'没有匹配的调用记录':'调用记录暂不可用' }}</td></tr></tbody></table></div>
    <div v-if="filtered.length" class="admin-toolbar"><span>共 {{ filtered.length }} 条 · 第 {{ currentPage }}/{{ pageCount }} 页</span><button class="mini" :disabled="currentPage<=1" @click="currentPage--">上一页</button><button class="mini" :disabled="currentPage>=pageCount" @click="currentPage++">下一页</button></div>
    <section v-if="selected" class="table-card admin-document"><div class="admin-title"><h3>调用 {{ selected.id.slice(0,8) }} 的工具顺序</h3><button class="mini" @click="selected=null">关闭详情</button></div><ol class="admin-tool-trace"><li v-for="(t,i) in selected.tools" :key="i"><b>{{ t.tool }}</b><span>{{ t.elapsedMs }}ms · {{ t.success?'成功':'失败' }}</span><p v-if="t.errorPresent" class="admin-error">该工具记录了错误；此技术视图不展示可能含患者信息的原始错误。</p></li></ol><p v-if="!selected.tools.length">这条记录没有保存工具轨迹。</p></section>
    <p v-if="selected?.traceId" class="admin-notice">本轮关联标识：{{ selected.traceId }}（可在关键词框筛选当前已加载记录；旧记录无标识不补造）</p>
    <h3>最近检索执行</h3><p>保留本进程最近 100 条执行摘要，重启清空；不保存患者问题或知识片段。这里的检索记录包含管理员调试请求。</p><div class="table-card admin-scroll"><table><thead><tr><th>时间</th><th>实际路径</th><th>向量状态</th><th>重排状态</th><th>候选 / 保留</th><th>耗时</th></tr></thead><tbody><tr v-for="e in events" :key="e.id"><td>{{ time(e.time) }}</td><td>{{ e.mode }}</td><td>{{ label(e.semanticStatus) }}</td><td>{{ label(e.rerankStatus) }}</td><td>{{ e.candidates }} / {{ e.selected }}</td><td>{{ e.elapsedMs }}ms</td></tr><tr v-if="!events.length"><td colspan="6" class="empty">当前没有可展示的检索执行记录</td></tr></tbody></table></div>
  </div>
</template>
