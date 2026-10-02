<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '../../api'
import AssessmentCard from './components/AssessmentCard.vue'
import type { Assessment, ChatSession, Conversation, Doctor, HumanReview, ResponseProvenance, Result } from './types'

const props=defineProps<{sessions:ChatSession[],initialSymptom?:string,bookingRunning:boolean}>()
const emit=defineEmits<{book:[doctor:Doctor,sessionId:string],updated:[],notify:[message:string]}>()
const question=ref(''), activeConversation=ref<Conversation|null>(null), eligible=ref(false)
const pendingMessage=ref(''), triage=ref<Result|null>(null), triageStatus=ref(''), triageRunning=ref(false)
const waitSeconds=ref(0)
let waitTimer:number|undefined
const sessionList=ref<ChatSession[]>(props.sessions)
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
function answerMethod(meta:ResponseProvenance):string{
  if(meta.modelStatus==='SAFETY_RULE')return '危险信号由安全规则直接处理，未等待模型判断'
  if(meta.modelStatus==='LIVE')return 'AI 模型已参与回答'
  if(meta.modelStatus==='LIVE_UNGROUNDED')return 'AI 模型回答一般问题；本次无可引用知识资料'
  if(meta.modelStatus.startsWith('DEMO'))return '演示规则回答，未调用外部 AI 模型'
  if(meta.modelStatus.startsWith('FALLBACK'))return '模型暂不可用，已使用保守回答'
  if(meta.modelStatus==='VALIDATION_BLOCKED')return '模型回答未通过安全校验，已使用保守回答'
  return '未生成模型回答；建议补充信息或人工咨询'
}
async function startTriage(){
  if(triageRunning.value)return
  const content=normalizeUserText(question.value)
  if(!content)return
  const previousUserCount=activeConversation.value?.messages.filter(message=>message.role==='USER').length||0
  question.value='';triageRunning.value=true;waitSeconds.value=0;triageStatus.value='正在检查危险信号…';pendingMessage.value=content
  waitTimer=window.setInterval(()=>{waitSeconds.value++;if(waitSeconds.value>=2)triageStatus.value=`正在检索知识并等待回答，已等待 ${waitSeconds.value} 秒…`},1000)
  try{
    if(!activeConversation.value)activeConversation.value=await api<Conversation>('/triage/sessions',{method:'POST',body:JSON.stringify({adultConfirmed:eligible.value,forSelfConfirmed:eligible.value,notPregnantConfirmed:eligible.value})})
    const result=await api<Conversation>(`/triage/sessions/${activeConversation.value.session.id}/turns`,{method:'POST',body:JSON.stringify({content})})
    applyConversation(result)
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
  }finally{if(waitTimer!==undefined)window.clearInterval(waitTimer);waitTimer=undefined;pendingMessage.value='';triageRunning.value=false}
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
        <details v-if="message.role==='ASSISTANT' && message.provenance" class="answer-provenance"><summary>本次回答如何产生</summary><p>{{ answerMethod(message.provenance) }}</p><p>{{ message.provenance.modelStatus==='SAFETY_RULE'?'危险提醒优先于知识检索':message.provenance.knowledgeHits?`参考了 ${message.provenance.knowledgeHits} 条医学知识片段`:'未找到可引用的医学知识片段' }}</p><p>{{ message.provenance.localToolCalls?`执行了 ${message.provenance.localToolCalls} 项本地工具${message.provenance.toolFailures?`，其中 ${message.provenance.toolFailures} 项失败`:''}`:'未执行本地工具' }}。这里的本地工具不代表已连接独立医院 MCP 服务。</p></details>
        <AssessmentCard v-for="anchor in assessmentsForMessage(message.id)" :key="anchor.version" :anchor="anchor" :assessments="activeConversation?.assessments || []" :booking-running="bookingRunning" @book="(doctor,sessionId)=>emit('book',doctor,sessionId)" @review="requestHumanReview" />
      </template>
      <div v-if="pendingMessage" class="bubble user">{{ pendingMessage }}</div>
      <div v-if="triageRunning" class="bubble ai loading" role="status">{{ triageStatus }}</div>
    </div>
    <div class="disclaimer">预问诊会先回答一般健康问题、识别危险信号并按需追问；信息足够后才生成挂号建议。它不构成诊断或处方。</div>
    <div v-if="activeConversation?.session.status==='待补充信息'" class="triage-stage-note">当前处于预问诊阶段：请回答助手刚提出的关键问题。补充信息会保留在本会话中，后续分诊结果将作为新版本保存。</div>
    <div v-if="activeConversation?.session.status==='建议尽快就医'" class="triage-stage-note">本次结果建议尽快到线下医疗机构评估，因此不提供模拟号源预约。若症状加重或出现胸痛、呼吸困难、意识改变等情况，请立即前往急诊或拨打 120。</div>
    <div v-if="activeConversation?.humanReview" class="human-review-status">人工导诊申请已提交 · {{ activeConversation.humanReview.status==='PENDING'?'等待处理':activeConversation.humanReview.status }}</div>
    <div v-else-if="activeConversation" class="manual-review-access"><button type="button" class="review-button" @click="requestHumanReview(activeConversation.session.id,'患者主动申请人工导诊')">需要人工导诊？提交申请</button><small>演示系统仅记录申请，当前不保证实时人工响应。出现急症请立即线下求助。</small></div>
    <label v-if="!activeConversation" class="eligibility-check"><input v-model="eligible" type="checkbox"> 我已年满 18 岁、为本人提问，且不处于孕产期；急症请立即线下求助。</label>
    <form class="chat-input" @submit.prevent="startTriage">
      <textarea v-model="question" placeholder="例如：我最近咳嗽得厉害，胸闷，痰多。"></textarea>
      <button class="primary" type="submit" :disabled="triageRunning || !question.trim() || (!activeConversation && !eligible)">{{ triageRunning ? '生成中…' : '发送' }}</button>
    </form>
  </div>
</section>
</template>
