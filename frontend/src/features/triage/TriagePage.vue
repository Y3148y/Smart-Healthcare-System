<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, streamResult } from '../../api'
import AssessmentCard from './components/AssessmentCard.vue'
import type { Assessment, ChatSession, Conversation, Doctor, HumanReview, ResponseProvenance, Result } from './types'

const props=defineProps<{sessions:ChatSession[],initialSymptom?:string,bookingRunning:boolean}>()
const emit=defineEmits<{book:[doctor:Doctor,sessionId:string],updated:[],notify:[message:string]}>()
const question=ref(''), activeConversation=ref<Conversation|null>(null), eligible=ref(false)
const pendingMessage=ref(''), triage=ref<Result|null>(null), triageStatus=ref(''), triageRunning=ref(false)
const sessionList=ref<ChatSession[]>(props.sessions)
const elapsedSeconds=ref(0), replyDurations=ref<Record<string,number>>({})
let elapsedTimer:ReturnType<typeof setInterval>|undefined
onUnmounted(()=>{if(elapsedTimer)clearInterval(elapsedTimer)})
watch(()=>props.sessions,value=>sessionList.value=value)
onMounted(()=>{if(props.initialSymptom)question.value=props.initialSymptom})

function normalizeUserText(value:string){
  const text=value.trim()
  if(!/&#(?:x[0-9a-f]+|\d+);/i.test(text))return text
  const decoder=document.createElement('textarea')
  decoder.innerHTML=text
  return decoder.value
}
function applyConversation(value:Conversation){
  activeConversation.value=value
  triage.value=value.assessments[value.assessments.length-1]?.result||null
}
function assessmentsForMessage(messageId:string):Assessment[]{
  return activeConversation.value?.assessments.filter(assessment=>assessment.assistantMessageId===messageId)||[]
}
function evidenceMethod(meta:ResponseProvenance):string{
  if(meta.modelStatus==='SAFETY_RULE')return '危险提醒优先于知识检索'
  const evidence=meta.answerEvidence
  if(evidence?.retrievalStatus==='DEPENDENCY_UNAVAILABLE')return '资料检索暂不可用，不代表没有相关资料'
  if(!meta.knowledgeHits)return '未找到可引用的医学知识片段'
  if(!evidence)return `检索到 ${meta.knowledgeHits} 条资料；旧记录未采集回答实际引用情况`
  if(evidence.supportReview?.status==='REJECTED')return `检索到 ${meta.knowledgeHits} 条资料，但生成内容未通过本轮问题与资料依据核对，未采用`
  if(evidence.supportReview?.status==='UNAVAILABLE')return `检索到 ${meta.knowledgeHits} 条资料；回答依据核对暂未完成，未采用生成内容`
  if(evidence.validationStatus==='REFERENCE_INTEGRITY_PASSED')return `检索到 ${meta.knowledgeHits} 条资料，本次回答引用 ${evidence.adoptedReferenceIds.length} 条。引用不等于已确认您的个人病情`
  return `检索到 ${meta.knowledgeHits} 条资料；本次未采用经引用校验的模型回答`
}
function answerMethod(meta:ResponseProvenance):string{
  if(meta.modelStatus==='SAFETY_RULE')return '危险信号由安全规则直接处理，未等待模型判断'
  // A compliance refusal is a deliberate terminal answer, not a failure to answer. It must not
  // fall through to the fallback text, which tells the patient to supplement information — but
  // supplementing never changes a refusal, so that advice is simply wrong here.
  if(meta.modelStatus==='POLICY_REFUSAL')return '合规拒答：系统不提供诊断或处方。该答复由确定性策略直接给出，不经过模型，也不随您补充的信息而改变；如需就医方向，请改问就医相关问题或申请人工导诊'
  if(meta.modelStatus==='CLARIFICATION')return '系统在等你补充关键信息，暂不给出科室推荐或号源。该追问由确定性策略直接给出，不经过模型'
  if(meta.modelStatus==='LIVE')return 'AI 模型已参与回答'
  if(meta.modelStatus==='LIVE_UNGROUNDED')return 'AI 模型回答一般问题；本次无可引用知识资料'
  if(meta.modelStatus.startsWith('DEMO'))return '演示规则回答，未调用外部 AI 模型'
  if(meta.modelStatus.startsWith('FALLBACK'))return meta.answerEvidence?.failure?'AI 本次调用未完成，已显示失败提示，未采用模型生成内容':'模型暂不可用，已使用保守回答'
  if(meta.modelStatus==='VALIDATION_BLOCKED')return '生成内容未通过校验或依据核对，未采用模型回答'
  return '未生成模型回答；建议补充信息或人工咨询'
}
function serviceMessages(meta?:ResponseProvenance|null):string[]{
  return meta?.answerEvidence?.serviceNotice?.messages || []
}
async function startTriage(){
  if(triageRunning.value)return
  const content=normalizeUserText(question.value)
  if(!content)return
  const previousUserCount=activeConversation.value?.messages.filter(message=>message.role==='USER').length||0
  question.value='';triageRunning.value=true;triageStatus.value='正在提交请求…';pendingMessage.value=content
  const started=performance.now()
  elapsedSeconds.value=0
  elapsedTimer=setInterval(()=>{elapsedSeconds.value=(performance.now()-started)/1000},250)
  try{
    if(!activeConversation.value)activeConversation.value=await api<Conversation>('/triage/sessions',{method:'POST',body:JSON.stringify({adultConfirmed:eligible.value,forSelfConfirmed:eligible.value,notPregnantConfirmed:eligible.value})})
    const stages:Record<string,string>={ACCEPTED:'请求已接收',SAFETY_CHECK:'正在检查危险信号…',KNOWLEDGE_RETRIEVAL:'正在检索知识依据…',ANSWER_GENERATION:'正在生成并校验回答…',SCHEDULE_LOOKUP:'正在查询模拟号源…',SAVING:'正在保存本轮结果…'}
    const result=await streamResult<Conversation>(`/triage/sessions/${activeConversation.value.session.id}/turns/stream`,{content},stage=>{triageStatus.value=stages[stage]||'正在处理…'})
    applyConversation(result)
    const replies=result.messages.filter(message=>message.role==='ASSISTANT')
    const lastReply=replies[replies.length-1]
    if(lastReply)replyDurations.value[lastReply.id]=(performance.now()-started)/1000
    triageStatus.value=triage.value?'分诊建议已生成':'等待您补充信息'
    sessionList.value=await api<ChatSession[]>('/triage/sessions')
    emit('updated')
  }catch(e:any){
    triageStatus.value='分诊请求失败'
    let saved=false
    if(activeConversation.value){
      try{
        const latest=await api<Conversation>(`/triage/sessions/${activeConversation.value.session.id}`)
        const userMessages=latest.messages.filter(message=>message.role==='USER')
        saved=userMessages.length>previousUserCount && userMessages[userMessages.length-1]?.content===content
        if(saved)applyConversation(latest)
      }catch{}
    }
    if(!saved)question.value=content
    emit('notify',saved?'症状已保存；本次分析未完成，请补充信息后重试':`分诊失败：${e?.message||'请检查后端服务'}`)
  }finally{if(elapsedTimer)clearInterval(elapsedTimer);elapsedTimer=undefined;pendingMessage.value='';triageRunning.value=false}
}
async function openConversation(id:string){
  if(triageRunning.value)return
  try{applyConversation(await api<Conversation>(`/triage/sessions/${id}`));question.value=''}
  catch(e:any){emit('notify',`会话加载失败：${e?.message||'请求失败'}`)}
}
function newConversation(){
  if(triageRunning.value)return
  activeConversation.value=null;triage.value=null;question.value='';pendingMessage.value='';eligible.value=false
}
async function requestHumanReview(sessionId:string,reason:string){
  if(!activeConversation.value||activeConversation.value.humanReview){emit('notify','该会话已提交人工导诊申请');return}
  try{
    const review=await api<HumanReview>(`/triage/sessions/${sessionId}/human-review`,{method:'POST',body:JSON.stringify({reason})})
    activeConversation.value={...activeConversation.value,humanReview:review}
    emit('notify','人工导诊申请已记录，状态：等待处理')
  }catch(e:any){emit('notify',`人工导诊申请失败：${e?.message||'请求失败'}`)}
}
</script>

<template>
<section class="content triage-layout">
  <aside class="conversation-list">
    <div class="side-title">智能预问诊 <button type="button" @click="newConversation">＋ 新会话</button></div>
    <button v-for="s in sessionList" :key="s.id" type="button" class="session session-button" :class="{selected:activeConversation?.session.id===s.id}" @click="openConversation(s.id)"><b>{{ s.title }}</b><label>{{ s.status }}</label><p>{{ s.preview }}</p><small>{{ new Date(s.updatedAt).toLocaleString() }}</small></button>
    <div v-if="!sessionList.length" class="empty">暂无分诊会话<br>开始描述您的症状吧</div>
  </aside>
  <div class="chat-pane">
    <div class="chat-heading"><h2>AI 门诊预问诊</h2><span>{{ triageRunning ? '正在生成回答' : activeConversation?.session.status || '等待您的描述' }}</span></div>
    <div class="chat-scroll" role="log" aria-live="polite">
      <div v-if="!activeConversation?.messages.length && !pendingMessage" class="bubble ai">您好，我是门诊预问诊助手。为了帮助您更好地分诊，请描述不适部位、持续时间及是否伴随发热、胸痛或呼吸困难。</div>
      <template v-for="message in activeConversation?.messages || []" :key="message.id">
        <div class="bubble" :class="message.role==='USER'?'user':'ai'">{{ message.content }}</div>
        <small v-if="replyDurations[message.id]!==undefined" class="response-duration">本次请求用时 {{ replyDurations[message.id].toFixed(1) }} 秒</small>
        <aside v-if="message.role==='ASSISTANT' && !assessmentsForMessage(message.id).length && serviceMessages(message.provenance).length" class="service-notice"><b>本轮预约查询</b><p v-for="(item,index) in serviceMessages(message.provenance)" :key="index">{{ item }}</p></aside>
        <details v-if="message.role==='ASSISTANT' && message.provenance" class="answer-provenance"><summary>本次回答如何产生</summary><p>{{ answerMethod(message.provenance) }}</p><p>{{ evidenceMethod(message.provenance) }}</p><p>{{ message.provenance.localToolCalls?`执行了 ${message.provenance.localToolCalls} 项本地工具${message.provenance.toolFailures?`，其中 ${message.provenance.toolFailures} 项失败`:''}`:'未执行本地工具' }}。这里的本地工具不代表已连接独立医院 MCP 服务。</p></details>
        <AssessmentCard v-for="anchor in assessmentsForMessage(message.id)" :key="anchor.version" :anchor="anchor" :assessments="activeConversation?.assessments || []" :booking-running="bookingRunning" :current-session-status="activeConversation?.session.status" @book="(doctor,sessionId)=>emit('book',doctor,sessionId)" @review="requestHumanReview" />
      </template>
      <div v-if="pendingMessage" class="bubble user">{{ pendingMessage }}</div>
      <div v-if="triageRunning" class="bubble ai loading" role="status">{{ triageStatus }}</div>
      <small v-if="triageRunning" class="response-duration">已用时 {{ elapsedSeconds.toFixed(1) }} 秒</small>
    </div>
    <div class="chat-boundary">仅供挂号参考，不构成诊断；如有急症请立即线下就医。</div>
    <div v-if="activeConversation?.session.status==='建议尽快就医'" class="triage-stage-note">本次结果建议尽快到线下医疗机构评估，因此不提供模拟号源预约。若症状加重或出现胸痛、呼吸困难、意识改变等情况，请立即前往急诊或拨打 120。</div>
    <div v-if="activeConversation?.humanReview" class="human-review-status">人工导诊申请已提交 · {{ activeConversation.humanReview.status==='PENDING'?'等待处理':activeConversation.humanReview.status }}</div>
    <div v-else-if="activeConversation" class="manual-review-access"><button type="button" class="review-button" @click="requestHumanReview(activeConversation.session.id,'患者主动申请人工导诊')">申请人工导诊（非实时）</button></div>
    <div v-if="!activeConversation" class="eligibility-row"><label class="eligibility-check"><input v-model="eligible" type="checkbox"> 我已年满 18 岁、为本人提问，且不处于孕产期。</label><details class="eligibility-details"><summary>适用范围与孕产提醒</summary><p>本系统不提供孕产期常规预问诊。如果您正在或可能怀孕、近期分娩，请停止普通分诊并咨询线下医疗人员。孕期或可能怀孕时如有大量或持续出血，或伴剧烈腹痛、头晕晕厥，请立即寻求急诊帮助；少量出血也请尽快联系线下医疗人员。</p><p>勾选仅是一次性自我声明，不代表系统已核实孕产状态；后续提到可能怀孕时，系统会暂停普通号源推荐。急症请立即线下求助。</p></details></div>
    <form class="chat-input" @submit.prevent="startTriage">
      <textarea v-model="question" placeholder="例如：我最近咳嗽得厉害，胸闷，痰多。"></textarea>
      <button class="primary" type="submit" :disabled="triageRunning || !question.trim() || (!activeConversation && !eligible)">{{ triageRunning ? '生成中…' : '发送' }}</button>
    </form>
  </div>
</section>
</template>

<style scoped>
.response-duration{display:block;color:#8994a3;font-size:12px;margin:4px 0 12px 16px}
.service-notice{margin:8px 0 12px;padding:12px 16px;border:1px solid #dce7f2;border-radius:12px;background:#f7faff;color:#526779;font-size:14px}
.service-notice p{margin:6px 0 0}
</style>
