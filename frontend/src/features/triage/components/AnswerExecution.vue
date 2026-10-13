<script setup lang="ts">
import {computed} from 'vue'
import type {ResponseProvenance} from '../types'
const props=defineProps<{meta:ResponseProvenance}>()
const events=computed(()=>props.meta.turnTrace?.executions)
const retrievals=computed(()=>events.value?.filter(e=>e.kind==='RETRIEVAL')||[])
const tools=computed(()=>events.value?.filter(e=>e.kind==='TOOL')||[])
const flag=(value:boolean|null|undefined)=>value==null?'未记录':value?'是':'否'
const generation=computed(()=>props.meta.answerEvidence?.generation)
const phaseLabel=computed(()=>({NOT_RUN:'未执行',PROMPT_PREPARATION:'停留在请求准备阶段',MODEL_CALL:'进入模型调用阶段，但未记录成功返回',OUTPUT_VALIDATION:'模型已返回，进入输出校验'}[generation.value?.phase||'']||'未记录'))
</script>
<template>
  <section class="execution" aria-label="本轮调用详情">
    <p>LLM 生成请求：{{ phaseLabel }}。{{ generation?.inputTokens!=null?`输入 ${generation.inputTokens} Token；`:'' }}{{ generation?.outputTokens!=null?`输出 ${generation.outputTokens} Token。`:'' }}</p>
    <p v-if="meta.answerEvidence?.supportReview">回答依据核对：{{ meta.answerEvidence.supportReview.status }}（模型核对，不是医学审核）。</p>
    <p v-if="!events">旧记录未保存详细执行链，RRF 与 rerank 状态未知。</p>
    <template v-else>
      <p>本地工具：{{ tools.length }} 次。工具由后端执行，不等于通过外部 MCP 服务调用。</p>
      <ul v-if="tools.length"><li v-for="(item,i) in tools" :key="i">{{ item.name }} · {{ item.status==='SUCCEEDED'?'执行成功':'执行失败' }} · {{ item.elapsedMs }}ms</li></ul>
      <p>RAG 检索：{{ retrievals.length ? `执行 ${retrievals.length} 次` : '本轮未记录混合检索执行' }}。检索成功不等于回答采用了资料。</p>
      <article v-for="(item,i) in retrievals" :key="i">
        <b>检索 {{ i+1 }}</b>
        <p>RRF 已融合候选：{{ flag(item.rrfUsed) }}；rerank 已开启：{{ flag(item.rerankEnabled) }}；成功使用 rerank：{{ flag(item.rerankUsed) }}</p>
        <p>保留 {{ item.selectedCount ?? '未知' }} 个片段 · {{ item.elapsedMs }}ms</p>
        <small>路径：{{ item.name }}<br>向量 / 重排状态：{{ item.status }}</small>
      </article>
    </template>
    <details v-if="meta.answerEvidence?.retrievedReferences?.length">
      <summary>检索资料简述（原文摘录，不代表已引用）</summary>
      <article v-for="(item,i) in meta.answerEvidence.retrievedReferences" :key="i">
        <b>{{ item.title }}</b><small v-if="item.sectionPath?.length"> · {{ item.sectionPath.join(' / ') }}</small>
        <p>{{ item.excerpt.slice(0,160) }}{{ item.excerpt.length>160?'…':'' }}</p>
      </article>
    </details>
  </section>
</template>
<style scoped>
.execution{font-size:13px;color:#53677b;overflow-wrap:anywhere}article{border-top:1px solid #e4eaf0;padding:8px 0}p{margin:6px 0}ul{padding-left:20px}summary{cursor:pointer}small{white-space:normal}
</style>
