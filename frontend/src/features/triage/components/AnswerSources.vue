<script setup lang="ts">
import {computed} from 'vue'
import type {ResponseProvenance} from '../types'
import {sourceLinks} from '../sourceLinks'
const props=defineProps<{evidence?:ResponseProvenance['answerEvidence']}>()
const references=computed(()=>{
  const evidence=props.evidence
  if(evidence?.validationStatus!=='REFERENCE_INTEGRITY_PASSED' || evidence.supportReview?.status!=='PASSED')return []
  const ids=new Set(evidence.adoptedReferenceIds||[])
  const refs=evidence.retrievedReferences||[]
  // Ambiguous identities cannot be presented as a single adopted source.
  return refs.filter(ref=>ids.has(ref.id)&&refs.filter(other=>other.id===ref.id).length===1)
})
</script>
<template>
  <details v-if="references.length" class="answer-sources">
    <summary>本次回答引用的资料（{{ references.length }}）</summary>
    <article v-for="reference in references" :key="reference.id">
      <strong>{{ reference.title }}</strong>
      <small v-if="reference.sectionPath?.length">{{ reference.sectionPath.join(' / ') }}</small>
      <p>{{ reference.excerpt }}</p>
      <a v-for="link in sourceLinks(reference.source)" :key="link.href" :href="link.href" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
    </article>
  </details>
</template>
<style scoped>
.answer-sources{margin:8px 0;color:#63758a;font-size:13px;max-width:100%;overflow-wrap:anywhere}
summary{cursor:pointer}article{padding:10px 0;border-top:1px solid #e6edf4}strong,small{display:block}p{white-space:pre-wrap;margin:6px 0}a{margin-right:12px}
</style>
