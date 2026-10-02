<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Assessment, Doctor } from '../types'

const props=defineProps<{anchor:Assessment,assessments:Assessment[],bookingRunning:boolean}>()
const emit=defineEmits<{book:[doctor:Doctor,sessionId:string],review:[sessionId:string,reason:string]}>()
const selectedVersion=ref(props.anchor.version)
const traceExpanded=ref(false)
const shown=computed(()=>props.assessments.find(item=>item.version===selectedVersion.value)||props.anchor)
const latestVersion=computed(()=>props.assessments[props.assessments.length-1]?.version)
const isBookable=computed(()=>shown.value.version===latestVersion.value && shown.value.result.grounded
  && shown.value.result.riskLevel!=='待补充信息' && shown.value.result.riskLevel!=='紧急')
const explanation=computed(()=>{
  if(props.anchor.version===1)return '第 1 版是本次会话首次分诊的原始快照；后续补充症状不会改写此结果。'
  const previous=props.assessments.find(item=>item.version===props.anchor.version-1)
  if(!previous)return `第 ${props.anchor.version} 版结合此前描述与本轮新增信息生成；之前的分诊结果仍可切换查看。`
  const changes:string[]=[]
  if(previous.result.department!==props.anchor.result.department)changes.push(`主要就医方向由“${previous.result.department}”调整为“${props.anchor.result.department}”`)
  if(previous.result.riskLevel!==props.anchor.result.riskLevel)changes.push(`风险提示由“${previous.result.riskLevel}”调整为“${props.anchor.result.riskLevel}”`)
  const oldDepartments=previous.result.candidates?.map(item=>item.department).join('、')||previous.result.department
  const newDepartments=props.anchor.result.candidates?.map(item=>item.department).join('、')||props.anchor.result.department
  if(oldDepartments!==newDepartments)changes.push(`相关科室由“${oldDepartments}”调整为“${newDepartments}”`)
  return `第 ${props.anchor.version} 版结合此前描述与本轮新增信息生成；${changes.length?`与上一版相比，${changes.join('；')}。`:'主要就医方向和风险等级与上一版一致，具体理由及医生请查看当前版本。'}上一版原始结果保持不变。`
})
function selectVersion(version:number){selectedVersion.value=version;traceExpanded.value=false}
function evidenceSources(source:string){return (source||'').split('|').map(item=>item.trim()).filter(item=>item.startsWith('http'))}
function sourceLabel(source:string){try{return new URL(source).hostname}catch{return source}}
</script>

<template>
  <div class="result-card versioned-result">
    <div class="assessment-tabs"><span>本轮分诊版本</span><button v-for="candidate in assessments.filter(item=>item.version<=anchor.version)" :key="candidate.version" type="button" :class="{selected:shown.version===candidate.version}" @click="selectVersion(candidate.version)">第 {{ candidate.version }} 版</button></div>
    <p class="version-explanation">{{ explanation }}</p>
    <p v-if="shown.version!==anchor.version" class="historic-note">当前正在对照第 {{ shown.version }} 版的完整原始结果；本轮新结果是第 {{ anchor.version }} 版。</p>
    <div class="result-title">智能分诊建议 · 第 {{ shown.version }} 版 <small class="model-badge" :class="shown.result.modelStatus==='LIVE'?'live':'fallback'">{{ shown.result.modelStatus==='LIVE' ? `大模型已回答 · ${shown.result.modelName}` : shown.result.modelStatus==='SAFETY_RULE' ? '安全规则已接管' : shown.result.modelStatus==='EVIDENCE_BLOCKED' ? '知识依据不足 · 已停止生成' : shown.result.modelStatus==='VALIDATION_BLOCKED' ? '模型回答未通过安全校验' : shown.result.modelStatus==='FALLBACK' ? '模型暂不可用 · 已使用规则回答' : '本地规则回答' }}</small> <label :class="shown.result.riskLevel==='紧急'?'danger':''">风险等级：{{ shown.result.riskLevel }}</label><label>置信度：{{ shown.result.confidence }}%</label></div>
    <div class="recommend"><div><small>建议就医方向</small><b>{{ shown.result.department }}</b></div><div><small>模拟可约医生</small><b>{{ shown.result.doctor?.name || (shown.result.riskLevel==='紧急' ? '请立即急诊就医' : '暂无模拟号源') }}</b></div></div>
    <div v-if="shown.result.riskLevel==='紧急'" class="emergency-alert" role="alert"><strong>⚠ 紧急就医提醒</strong><span>{{ shown.result.safetyTip }}</span><div v-if="shown.result.safetyAssessment?.signals?.length" class="safety-signals"><span v-for="signal in shown.result.safetyAssessment.signals" :key="signal.ruleCode"><b>{{ signal.category }}</b>：{{ signal.evidence }}（{{ signal.ruleCode }}）</span></div><small>规则版本：{{ shown.result.safetyAssessment?.policyVersion }}。系统已停止普通号源推荐。</small><div class="emergency-actions"><a href="tel:120" class="primary">拨打 120</a><button type="button" class="review-button" @click="emit('review',shown.result.sessionId,'危险信号触发后申请人工复核')">申请人工导诊复核</button></div></div>
    <div v-if="shown.result.candidates?.length>1" class="candidate-list"><b>其他相关科室及模拟医生 <small>多个方向仅供参考，不代表多项诊断</small></b><div v-for="candidate in shown.result.candidates" :key="candidate.department" class="candidate-option"><div><strong>{{ candidate.department }}</strong><span>{{ candidate.reason }}</span><small>{{ candidate.doctor ? `${candidate.doctor.name} · ${candidate.doctor.title} · ${candidate.doctor.date} ${candidate.doctor.period}` : '暂无可用模拟号源' }}</small></div><button v-if="candidate.doctor && isBookable" type="button" class="primary" :disabled="bookingRunning" @click="emit('book',candidate.doctor,shown.result.sessionId)">模拟预约</button></div></div>
    <p v-if="shown.result.riskLevel!=='紧急'" class="safety">{{ shown.result.safetyTip }}</p>
    <div class="grounding-status" :class="{blocked:!shown.result.grounded}"><b>{{ shown.result.grounded?'检索到相关知识片段':'未检索到足够相关的知识' }}</b><span>{{ shown.result.groundingMessage }}</span></div>
    <div class="evidence"><b>RAG 知识依据</b><article v-for="e in shown.result.evidence" :key="`${e.title}-${e.excerpt}`"><strong>{{ e.title }} · 相关度 {{ Math.round(e.score*100) }}%</strong><span>{{ e.excerpt }}</span><small v-if="evidenceSources(e.source).length" class="evidence-source">来源：<a v-for="source in evidenceSources(e.source)" :key="source" :href="source" target="_blank" rel="noopener noreferrer">{{ sourceLabel(source) }}</a></small></article><p v-if="!shown.result.evidence.length" class="historic-note">没有达到相关度阈值的知识片段，系统已拒绝无依据生成。</p></div>
    <button class="text-btn" type="button" @click="traceExpanded=!traceExpanded">{{ traceExpanded?'收起':'展开' }} Agent 工具调用轨迹（{{ shown.result.tools.length }}）</button>
    <div v-if="traceExpanded" class="trace"><div v-for="t in shown.result.tools" :key="t.tool" :class="{failed:!t.success}"><b>{{ t.label }}</b><span>{{ t.outcome }}<em v-if="t.error"> · {{ t.error }}</em></span><small>{{ t.elapsedMs }}ms</small></div></div>
    <button v-if="shown.result.doctor && isBookable" type="button" class="primary" :disabled="bookingRunning" @click="emit('book',shown.result.doctor!,shown.result.sessionId)">{{ bookingRunning?'预约中…':`模拟预约 ${shown.result.doctor.name} 的号源` }}</button>
    <small v-else-if="shown.result.doctor" class="historic-note">历史版本仅供回看；如需预约，请切换到最新分诊版本。</small>
    <button v-if="shown.result.safetyAssessment?.humanReviewRecommended && shown.result.riskLevel!=='紧急'" type="button" class="review-button" @click="emit('review',shown.result.sessionId,'系统建议人工复核')">申请人工导诊</button>
  </div>
</template>
