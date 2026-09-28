<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '../../api'
import AssessmentCard from './components/AssessmentCard.vue'
import type { Assessment, ChatSession, Conversation, Doctor, Result } from './types'

const props=defineProps<{sessions:ChatSession[],initialSymptom?:string,bookingRunning:boolean}>()
const emit=defineEmits<{book:[doctor:Doctor,sessionId:string],updated:[],notify:[message:string]}>()
const question=ref(''), activeConversation=ref<Conversation|null>(null), eligible=ref(false)
const pendingMessage=ref(''), triage=ref<Result|null>(null), triageStatus=ref(''), triageRunning=ref(false)
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
async function startTriage(){
  if(triageRunning.value)return
  const content=normalizeUserText(question.value)
  if(!content)return
  const previousUserCount=activeConversation.value?.messages.filter(message=>message.role==='USER').length||0
  question.value='';triageRunning.value=true;triageStatus.value='正在分析症状与危险信号…';pendingMessage.value=content
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
  }finally{pendingMessage.value='';triageRunning.value=false}
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
        <AssessmentCard v-for="anchor in assessmentsForMessage(message.id)" :key="anchor.version" :anchor="anchor" :assessments="activeConversation?.assessments || []" :booking-running="bookingRunning" @book="(doctor,sessionId)=>emit('book',doctor,sessionId)" />
      </template>
      <div v-if="pendingMessage" class="bubble user">{{ pendingMessage }}</div>
      <div v-if="triageRunning" class="bubble ai loading">{{ triageStatus }}</div>
    </div>
    <div class="disclaimer">预问诊由 AI 引导描述症状，仅供挂号参考，不构成诊断意见</div>
    <label v-if="!activeConversation" class="eligibility-check"><input v-model="eligible" type="checkbox"> 我已年满 18 岁、为本人提问，且不处于孕产期；急症请立即线下求助。</label>
    <form class="chat-input" @submit.prevent="startTriage">
      <textarea v-model="question" placeholder="例如：我最近咳嗽得厉害，胸闷，痰多。"></textarea>
      <button class="primary" type="submit" :disabled="triageRunning || !question.trim() || (!activeConversation && !eligible)">{{ triageRunning ? '生成中…' : '发送' }}</button>
    </form>
  </div>
</section>
</template>
