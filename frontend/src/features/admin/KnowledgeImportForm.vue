<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '../../api'
import type { KnowledgeDocument } from './types'

const emit = defineEmits<{ saved: [document: KnowledgeDocument] }>()
const busy = ref(false), error = ref('')
const form = reactive({ title: '', body: '', sourceId: '', publisher: '', url: '',
  topics: '', population: '', exclusions: '', prerequisites: '', permissionEvidence: '',
  contentKind: 'source_extract', evidenceUse: 'general_information', confirmed: false })
const terms = (text: string) => text.split(/[,，\n]/).map(s => s.trim()).filter(Boolean)

async function readFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  error.value = ''
  if (!file) return
  try {
    if (!/\.(txt|md)$/i.test(file.name) || file.size === 0 || file.size > 100000)
      throw new Error('请选择 1–100000 字节的 TXT 或 Markdown 文件')
    form.body = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
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
    if (!form.confirmed || !form.permissionEvidence.trim()) throw new Error('请核对使用许可并填写依据')
    if (!form.title.trim() || !form.body.trim() || !form.sourceId.trim() || !form.publisher.trim())
      throw new Error('请完整填写标题、正文和来源信息')
    busy.value = true
    const document = await api<KnowledgeDocument>('/admin/knowledge/documents', {
      method: 'POST', body: JSON.stringify({ title: form.title.trim(), body: form.body,
        metadata: { schemaVersion: 1, language: 'zh-CN', contentKind: form.contentKind,
          sources: [{ sourceId: form.sourceId.trim(), publisher: form.publisher.trim(), url: url.href,
            fetchedAt: null, rawSha256: null }], topics: terms(form.topics), population: terms(form.population),
          exclusions: terms(form.exclusions), prerequisites: terms(form.prerequisites),
          evidenceUses: [form.evidenceUse], permissionStatus: 'permitted',
          permissionEvidence: form.permissionEvidence.trim() } })
    })
    emit('saved', document)
  } catch (e: any) { error.value = e.message || '提交失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="table-card admin-editor">
    <h3>录入有来源的资料</h3>
    <p>填写实际正文与来源，提交后仍需另行审批。许可声明不代表临床审核。</p>
    <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
    <form @submit.prevent="submit">
      <fieldset :disabled="busy">
        <legend>正文与来源</legend>
        <label>资料标题<input v-model="form.title" required maxlength="160"></label>
        <label>读取本地 UTF-8 正文<input type="file" accept=".txt,.md" @change="readFile"></label>
        <label>资料正文<textarea v-model="form.body" required maxlength="100000" rows="8"></textarea></label>
        <label>来源登记 ID<input v-model="form.sourceId" required maxlength="128" placeholder="填写真实来源标识"></label>
        <label>发布机构<input v-model="form.publisher" required maxlength="240"></label>
        <label>来源链接<input v-model="form.url" type="url" required maxlength="2000"></label>
        <label>内容类型<select v-model="form.contentKind"><option value="source_extract">来源摘录</option><option value="reviewed_summary">人工整理摘要</option></select></label>
        <label>证据用途<select v-model="form.evidenceUse"><option value="general_information">一般健康说明</option><option value="direction_reference">就诊方向参考</option><option value="warning_reference">危险信号参考</option></select></label>
        <details><summary>补充适用范围（可选，多项用逗号或换行分隔）</summary>
          <label>主题<input v-model="form.topics" maxlength="2000"></label>
          <label>适用人群<input v-model="form.population" maxlength="2000"></label>
          <label>排除人群<input v-model="form.exclusions" maxlength="2000"></label>
          <label>适用前提<input v-model="form.prerequisites" maxlength="2000"></label>
        </details>
        <label>使用许可依据<textarea v-model="form.permissionEvidence" required maxlength="2000" rows="3" placeholder="填写实际许可条款或授权依据，公开可访问不等于允许复制"></textarea></label>
        <label><input v-model="form.confirmed" type="checkbox" required>我已核对本次内容的使用许可；未核对时不提交</label>
        <button class="primary" type="submit">{{ busy ? '提交中…' : '提交待审批' }}</button>
      </fieldset>
    </form>
  </section>
</template>
