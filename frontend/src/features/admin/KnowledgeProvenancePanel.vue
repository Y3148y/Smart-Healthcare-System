<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../../api'
const props = defineProps<{ documentId: string }>()
interface MetadataResponse {
  status: string
  clinicalReview: string
  metadata?: { contentKind: string; topics: string[]; population: string[]; exclusions: string[];
    prerequisites: string[]; evidenceUses: string[]; permissionStatus: string; permissionEvidence: string | null;
    sources: { sourceId: string; publisher: string; url: string; fetchedAt: string | null; rawSha256: string | null }[] }
}
interface Chunk { chunkId: string; documentVersion: string; sectionPath: string[]; start: number; end: number;
  positionUnit: string; contentSha256: string; chunkingVersion: string }
const metadata = ref<MetadataResponse | null>(null), chunks = ref<Chunk[]>([]), error = ref(''), loading = ref(false)
let requestId = 0
watch(() => props.documentId, async id => {
  const current = ++requestId
  metadata.value = null; chunks.value = []; error.value = ''; loading.value = true
  try {
    const path = '/admin/knowledge/' + encodeURIComponent(id)
    const result = await Promise.all([api<MetadataResponse>(path + '/metadata'), api<Chunk[]>(path + '/chunks')])
    if (current !== requestId) return
    metadata.value = result[0]; chunks.value = result[1]
  } catch (e: any) { if (current === requestId) error.value = e.message || '来源与溯源读取失败' }
  finally { if (current === requestId) loading.value = false }
}, { immediate: true })
</script>

<template>
  <section aria-label="来源与溯源">
    <h4>来源与适用范围</h4>
    <p v-if="loading" role="status">正在读取来源声明…</p>
    <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
    <p v-if="metadata?.status === 'LEGACY_UNKNOWN'">旧资料没有结构化来源与适用范围声明；未知不等于已核验。</p>
    <template v-if="metadata?.metadata">
      <dl>
        <dt>使用许可声明</dt><dd>{{ metadata.metadata.permissionStatus }}</dd>
        <dt>许可依据</dt><dd>{{ metadata.metadata.permissionEvidence || '未填写' }}</dd>
        <dt>主题</dt><dd>{{ metadata.metadata.topics.join('、') || '未声明' }}</dd>
        <dt>适用人群</dt><dd>{{ metadata.metadata.population.join('、') || '未声明' }}</dd>
        <dt>排除人群</dt><dd>{{ metadata.metadata.exclusions.join('、') || '未声明' }}</dd>
        <dt>适用前提</dt><dd>{{ metadata.metadata.prerequisites.join('、') || '未声明' }}</dd>
        <dt>证据用途</dt><dd>{{ metadata.metadata.evidenceUses.join('、') }}</dd>
      </dl>
      <article v-for="source in metadata.metadata.sources" :key="source.sourceId" class="admin-segment">
        <b>{{ source.publisher }} · {{ source.sourceId }}</b><p>{{ source.url }}</p>
        <small>采集时间：{{ source.fetchedAt || '未知' }} · 原始快照哈希：{{ source.rawSha256 || '未知' }}</small>
      </article>
    </template>
    <small>来源声明和管理员审批不代表临床审核。当前适用范围字段尚未用于在线过滤。</small>
    <details v-if="chunks.length"><summary>片段溯源（{{ chunks.length }}）</summary>
      <article v-for="(chunk, i) in chunks" :key="chunk.chunkId" class="admin-segment">
        <b>片段 {{ i + 1 }} · {{ chunk.sectionPath.join(' / ') || '正文' }}</b>
        <p>原文位置：{{ chunk.start }}–{{ chunk.end }}（{{ chunk.positionUnit }}，末位置不包含）</p>
        <small>片段 ID：{{ chunk.chunkId }}<br>内容版本：{{ chunk.documentVersion }}<br>正文哈希：{{ chunk.contentSha256 }}<br>切分策略：{{ chunk.chunkingVersion }}</small>
      </article>
    </details>
  </section>
</template>
