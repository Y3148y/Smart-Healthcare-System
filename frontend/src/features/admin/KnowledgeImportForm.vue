<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import type { KnowledgeDocument } from './types'
import { parseKnowledgeIntake, type IntakeMetadata } from './knowledgeIntake'

const emit = defineEmits<{ saved: [document: KnowledgeDocument] }>()
interface Source { sourceId: string; publisher: string; url: string; fetchedAt: string | null; rawSha256: string | null }
interface InitialMetadata { language: string; contentKind: string; topics: string[]; population: string[]; permissionStatus?: string;
  exclusions: string[]; prerequisites: string[]; evidenceUses: string[]; permissionEvidence: string | null; sources: Source[] }
const props = defineProps<{ documentId?: string; initialMetadata?: InitialMetadata }>()
const busy = ref(false), error = ref('')
const intakeMetadata = ref<IntakeMetadata>(), intakeWarnings = ref<string[]>([])
const form = reactive({ title: '', body: '', sourceId: '', publisher: '', url: '',
  topics: '', population: '', exclusions: '', prerequisites: '', permissionEvidence: '',
  contentKind: 'source_extract', evidenceUse: 'general_information', language: 'zh-CN', permissionStatus: 'pending', confirmed: false })
const terms = (text: string) => text.split(/[,，\n]/).map(s => s.trim()).filter(Boolean)
onMounted(() => {
  const initial = props.initialMetadata
  if (!initial) return
  const source = initial.sources[0]
  Object.assign(form, { sourceId: source?.sourceId || '', publisher: source?.publisher || '', url: source?.url || '',
    topics: initial.topics.join('\n'), population: initial.population.join('\n'),
    exclusions: initial.exclusions.join('\n'), prerequisites: initial.prerequisites.join('\n'),
    contentKind: initial.contentKind, evidenceUse: initial.evidenceUses[0] || 'general_information',
    permissionEvidence: initial.permissionEvidence || '' })
  form.language = initial.language
  form.permissionStatus = initial.permissionStatus || 'pending'
})

async function readFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  error.value = ''
  if (!file) return
  try {
    if (!/\.(txt|md|json)$/i.test(file.name) || file.size === 0 || file.size > 500000)
      throw new Error('请选择不超过 500000 字节的 TXT、Markdown 或待审 JSON 文件')
    const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    if (/\.json$/i.test(file.name)) {
      const candidate = await parseKnowledgeIntake(text)
      intakeMetadata.value = candidate.metadata
      intakeWarnings.value = candidate.warnings
      const source = candidate.metadata.sources[0]
      Object.assign(form, {title:candidate.title, body:candidate.body, sourceId:source.sourceId,
        publisher:source.publisher, url:source.url, language:candidate.metadata.language,
        topics:candidate.metadata.topics.join('\n'), population:candidate.metadata.population.join('\n'),
        exclusions:candidate.metadata.exclusions.join('\n'), prerequisites:candidate.metadata.prerequisites.join('\n'),
        contentKind:candidate.metadata.contentKind, evidenceUse:'', permissionStatus:'pending', permissionEvidence:'', confirmed:false})
      return
    }
    if (text.length > 100000) throw new Error('正文不能超过 100000 字符')
    intakeMetadata.value = undefined
    intakeWarnings.value = []
    form.body = text
    if (!form.title.trim()) form.title = file.name
  } catch (e: any) { error.value = e.message || '文件读取失败'; input.value = '' }
}

async function submit() {
  if (busy.value) return
  error.value = ''
  try {
    const url = new URL(form.url.trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
      throw new Error('来源必须是无账号密码的 HTTP 或 HTTPS 链接')
    if (form.permissionStatus === 'permitted' && (!form.confirmed || !form.permissionEvidence.trim()))
      throw new Error('请核对使用许可并填写依据')
    if (!form.evidenceUse || form.language === 'und') throw new Error('请核对资料语言并选择证据用途')
    if ((!props.documentId && (!form.title.trim() || !form.body.trim())) || !form.sourceId.trim() || !form.publisher.trim())
      throw new Error('请完整填写标题、正文和来源信息')
    busy.value = true
    const originalMetadata = props.initialMetadata || intakeMetadata.value
    const originalSource = originalMetadata?.sources[0]
    const unchangedSource = originalSource?.url === url.href && originalSource?.sourceId === form.sourceId.trim()
      && originalSource?.publisher === form.publisher.trim()
    const metadata = { schemaVersion: 1, language: form.language.trim(), contentKind: form.contentKind,
          sources: [{ sourceId: form.sourceId.trim(), publisher: form.publisher.trim(), url: url.href,
            fetchedAt: unchangedSource ? originalSource?.fetchedAt : null,
            rawSha256: unchangedSource ? originalSource?.rawSha256 : null }, ...(originalMetadata?.sources.slice(1) || [])],
          topics: terms(form.topics), population: terms(form.population),
          exclusions: terms(form.exclusions), prerequisites: terms(form.prerequisites),
          evidenceUses: [...new Set([form.evidenceUse, ...(originalMetadata?.evidenceUses.slice(1) || [])])], permissionStatus: form.permissionStatus,
          permissionEvidence: form.permissionEvidence.trim() || null }
    const document = await api<KnowledgeDocument>(props.documentId
      ? '/admin/knowledge/' + encodeURIComponent(props.documentId) + '/metadata' : '/admin/knowledge/documents', {
      method: props.documentId ? 'PUT' : 'POST', body: JSON.stringify(props.documentId ? metadata
        : { title: form.title.trim(), body: form.body, metadata })
    })
    emit('saved', document)
  } catch (e: any) { error.value = e.message || '提交失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="table-card admin-editor">
    <h3>{{ documentId ? '补正待审批资料的来源声明' : '录入有来源的资料' }}</h3>
    <p>填写实际正文与来源，提交后仍需另行审批。许可声明不代表临床审核。</p>
    <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
    <form @submit.prevent="submit">
      <fieldset :disabled="busy">
        <legend>正文与来源</legend>
        <template v-if="!documentId">
          <label>资料标题<input v-model="form.title" required maxlength="160"></label>
          <label>读取本地正文或待审包<input type="file" accept=".txt,.md,.json" @change="readFile"></label>
          <p v-if="intakeMetadata">已校验待审正文哈希；原始网页哈希是溯源声明，尚不等于许可或临床审核。</p>
          <p v-if="intakeWarnings.length" role="status">需要复核的抽取问题：{{ intakeWarnings.join('、') }}</p>
          <label>资料正文<textarea v-model="form.body" required maxlength="100000" rows="8"></textarea></label>
        </template>
        <p v-else>正文保持不变。其他已登记来源保留；修改首个来源后清空它的旧采集时间与快照哈希。</p>
        <label>来源登记 ID<input v-model="form.sourceId" required maxlength="128" placeholder="填写真实来源标识"></label>
        <label>发布机构<input v-model="form.publisher" required maxlength="240"></label>
        <label>来源链接<input v-model="form.url" type="url" required maxlength="2000"></label>
        <label>内容类型<select v-model="form.contentKind"><option value="source_extract">来源摘录</option><option value="reviewed_summary">人工整理摘要</option></select></label>
        <label>资料语言<input v-model="form.language" required maxlength="32" placeholder="例如 zh-CN 或 en"></label>
        <label>证据用途<select v-model="form.evidenceUse" required><option value="" disabled>请核对后选择</option><option value="general_information">一般健康说明</option><option value="direction_reference">就诊方向参考</option><option value="warning_reference">危险信号参考</option></select></label>
        <details><summary>补充适用范围（可选，多项用逗号或换行分隔）</summary>
          <label>主题<input v-model="form.topics" maxlength="2000"></label>
          <label>适用人群<input v-model="form.population" maxlength="2000"></label>
          <label>排除人群<input v-model="form.exclusions" maxlength="2000"></label>
          <label>适用前提<input v-model="form.prerequisites" maxlength="2000"></label>
        </details>
        <label>使用许可状态<select v-model="form.permissionStatus"><option value="pending">待核对（不可发布）</option><option value="restricted">受限（不可发布）</option><option value="permitted">已核对许可（仍需审批）</option></select></label>
        <label>使用许可依据<textarea v-model="form.permissionEvidence" :required="form.permissionStatus === 'permitted'" maxlength="2000" rows="3" placeholder="填写实际许可条款或授权依据，公开可访问不等于允许复制"></textarea></label>
        <label v-if="form.permissionStatus === 'permitted'"><input v-model="form.confirmed" type="checkbox" required>我已核对本次内容的使用许可</label>
        <button class="primary" type="submit">{{ busy ? '提交中…' : documentId ? '保存补正，保持待审批' : '提交待审批' }}</button>
      </fieldset>
    </form>
  </section>
</template>
