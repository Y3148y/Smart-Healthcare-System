<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, setToken, clearSession } from './api'
import { patientResources } from './patientResources'
import TriagePage from './features/triage/TriagePage.vue'
import HomePage from './features/home/HomePage.vue'
import BookingPage from './features/booking/BookingPage.vue'
import { bookingRequestKeys } from './features/booking/bookingRequestKeys'
import VisitsPage from './features/visits/VisitsPage.vue'
import AdminPage from './features/admin/AdminPage.vue'
import ProfilePage from './features/profile/ProfilePage.vue'
import type { Doctor, ChatSession, TimelineEvent } from './features/triage/types'
const params=new URLSearchParams(window.location.search), demo=params.get('demo'), loginFromForm=(params.get('login')||'').trim(), initialRole=(demo==='admin'||loginFromForm.toLowerCase()==='admin')?'ADMIN':'PATIENT', isDemoEntry=Boolean(demo||loginFromForm)
const readStored=(key:string)=>{try{return localStorage.getItem(key)}catch{return null}}
const store=(key:string,value:string)=>{try{localStorage.setItem(key,value)}catch{}}
// Demo routes authenticate first; rendering the app before that request succeeds caused false "login failed" states.
// A native demo-login form navigates with ?login=...; enter the matching demo role immediately,
// then obtain/refresh its API token in the background so the UI never appears to ignore a click.
const loggedIn=ref(Boolean(readStored('ai-hospital-token'))||isDemoEntry), loginName=ref('zhangsan'), password=ref('123456'), role=ref(isDemoEntry?initialRole:(readStored('ai-hospital-role')||initialRole)), displayName=ref(isDemoEntry?(initialRole==='ADMIN'?'系统管理员':loginFromForm.toLowerCase()==='lisi'?'李四':'张三'):(readStored('ai-hospital-name')||(initialRole==='ADMIN'?'系统管理员':'张三')))
const page=ref(params.get('page')||'home'), adminPage=ref(params.get('adminPage')||'dashboard'), doctors=ref<Doctor[]>([]), selectedDept=ref(params.get('department')||'全部'), visits=ref<any[]>([]), sessions=ref<ChatSession[]>([]), timeline=ref<TimelineEvent[]>([])
const bookingRunning=ref(false), toast=ref('')
const bookingKeys=bookingRequestKeys(()=>crypto.randomUUID())
const adminReady=ref(false)
const patientReady=ref(false)
const patientErrors=ref<string[]>([]), patientLoading=ref(false)
let patientLoadVersion=0
const depts=computed(()=>['全部',...new Set(doctors.value.map(d=>d.department))])
const isAdmin=computed(()=>role.value==='ADMIN')
const nav=[['home','首页'],['triage','智能预问诊'],['booking','预约挂号'],['visits','我的就诊'],['profile','我的资料']]
const showToast=(text:string)=>{toast.value=text;setTimeout(()=>toast.value='',2600)}
async function establishDemoSession():Promise<boolean>{
  if(!isDemoEntry)return Boolean(readStored('ai-hospital-token'))
  const requestedRole=initialRole
  for(let attempt=0;attempt<2;attempt++){
    try{
      const r:any=await api('/auth/login',{method:'POST',body:JSON.stringify({username:loginFromForm||(requestedRole==='ADMIN'?'admin':'zhangsan'),password:'123456'})})
      setToken(r.token);role.value=r.role;displayName.value=r.displayName
      store('ai-hospital-role',r.role);store('ai-hospital-name',r.displayName);store('ai-hospital-demo-role',requestedRole);store('ai-hospital-login',(loginFromForm||(requestedRole==='ADMIN'?'admin':'zhangsan')).toLowerCase())
      return true
    }catch{if(attempt===0)await new Promise(resolve=>setTimeout(resolve,500))}
  }
  showToast('后端认证暂不可用，请刷新后重试');return false
}
async function loadPatient(){
  const version=++patientLoadVersion;patientLoading.value=true
  try{
    const results=await patientResources({doctors:()=>api<Doctor[]>('/doctors'),visits:()=>api<any[]>('/appointments'),sessions:()=>api<ChatSession[]>('/triage/sessions'),timeline:()=>api<TimelineEvent[]>('/triage/timeline')})
    if(version!==patientLoadVersion)return
    patientErrors.value=[]
    for(const {name,outcome} of results){
      if(outcome.status==='rejected'){patientErrors.value.push(name);continue}
      if(name==='doctors')doctors.value=outcome.value as Doctor[]
      else if(name==='visits')visits.value=outcome.value as any[]
      else if(name==='sessions')sessions.value=outcome.value as ChatSession[]
      else timeline.value=outcome.value as TimelineEvent[]
    }
  }finally{if(version===patientLoadVersion)patientLoading.value=false}
}
const patientErrorNames=computed(()=>patientErrors.value.map(key=>({doctors:'医生排班',visits:'预约记录',sessions:'会话列表',timeline:'症状时间线'}[key]||key)).join('、'))
function patientRoute(next:string, extra:Record<string,string>={}) { return '?' + new URLSearchParams(readStored('ai-hospital-login')==='lisi'?{login:'lisi',page:next,...extra}:{demo:'patient',page:next,...extra}).toString() }
function adminRoute(next:string) { return '?' + new URLSearchParams({demo:'admin',page:'admin',adminPage:next}).toString() }
onMounted(async()=>{
  if(isDemoEntry){if(!await establishDemoSession()){loggedIn.value=false;return}loggedIn.value=true}
  else if(!loggedIn.value)return
  if(isAdmin.value){adminReady.value=true;return}
  await loadPatient()
  patientReady.value=true
  const symptom=params.get('symptom')
  if(page.value==='triage' && symptom)history.replaceState({},'',patientRoute('triage'))
  const doctorId=params.get('book')
  if(page.value==='booking' && doctorId){const doctor=doctors.value.find(d=>d.id===doctorId); history.replaceState({},'',patientRoute('booking')); if(doctor) await book(doctor)}
  const booked=params.get('booked')
  if(page.value==='visits' && booked){showToast(`预约成功：${booked}`);history.replaceState({},'',patientRoute('visits'))}
})
async function book(d:Doctor, sessionId='walk-in'){
  if(bookingRunning.value)return
  bookingRunning.value=true
  try{
    const a:any=await api('/appointments',{method:'POST',body:JSON.stringify({doctorId:d.id,sessionId,idempotencyKey:bookingKeys.get(d.id,d.date,d.period,sessionId)})})
    bookingKeys.complete(d.id,d.date,d.period,sessionId)
    window.location.assign(patientRoute('visits',{booked:a.registrationNo}))
  }catch(e:any){showToast(`预约结果待确认：${e.message}。请先查看我的就诊；本页重试相同号源会复用请求标识。`)}finally{bookingRunning.value=false}
}
function logout(){clearSession();patientLoadVersion++;history.replaceState({},'',window.location.pathname);loggedIn.value=false;adminReady.value=false;patientReady.value=false;role.value='PATIENT';page.value='home'}
const filteredDoctors=computed(()=>selectedDept.value==='全部'?doctors.value:doctors.value.filter(d=>d.department===selectedDept.value))
</script>

<template>
  <main v-if="!loggedIn" class="login-page"><div class="login-orb a"></div><div class="login-orb b"></div><section class="login-card"><div class="login-icon">⌁</div><h1>AI 智能导诊预约系统</h1><p>智能预问诊 · 智能分诊 · 医学知识库</p><form class="login-form" method="get"><input v-model.trim="loginName" name="login" autocomplete="username" placeholder="请输入账号（zhangsan、lisi 或 admin）"><input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码"><button class="primary wide" type="submit">登 录</button></form><a class="demo-admin-link" :href="adminRoute('dashboard')">进入管理端演示 →</a><small>演示账号：zhangsan / lisi / admin，密码任意</small></section></main>
  <main v-else class="app-shell">
    <header class="topbar"><div class="brand"><span class="brand-mark">⌁</span><b>AI 智能导诊{{ isAdmin?'管理平台':'预约系统' }}</b></div><template v-if="!isAdmin"><a v-for="n in nav" :key="n[0]" class="nav native-nav" :class="{active:page===n[0]}" :href="patientRoute(n[0])">{{ n[1] }}</a></template><span class="header-spacer"></span><a v-if="!isAdmin" class="user-menu native-nav" :href="adminRoute('dashboard')"><span class="avatar">{{ displayName.slice(0,1) }}</span>{{ displayName }}⌄</a><details v-else class="account-menu"><summary class="user-menu" :aria-label="`${displayName}，打开账号菜单`"><span class="avatar">{{ displayName.slice(0,1) }}</span>{{ displayName }}<span aria-hidden="true">⌄</span></summary><div class="account-popover"><strong>当前身份</strong><p>{{ displayName }} · 系统管理员</p><small>本机演示账号，不代表已接入医院生产管理系统。</small><button type="button" class="account-logout" @click="logout">退出登录</button></div></details><button v-if="!isAdmin" class="logout" @click="logout">退出</button></header>
    <template v-if="!isAdmin && page!=='admin'">
      <p v-if="!patientReady && patientLoading" role="status">正在加载患者数据…</p>
      <div v-if="patientErrors.length" class="triage-stage-note" role="status">{{ patientErrorNames }}暂未加载或刷新，不能据此判断没有记录。<button type="button" :disabled="patientLoading" @click="loadPatient">{{ patientLoading?'正在刷新…':'重新加载' }}</button></div>
      <HomePage v-if="page==='home'" :display-name="displayName" :route="patientRoute" />
      <TriagePage v-else-if="page==='triage'" :sessions="sessions" :initial-symptom="params.get('symptom') || ''" :booking-running="bookingRunning" @book="book" @updated="loadPatient" @notify="showToast" />
      <BookingPage v-else-if="page==='booking' && patientReady && !patientErrors.includes('doctors')" :doctors="filteredDoctors" :selected-dept="selectedDept" :departments="depts" :booking-running="bookingRunning" :route="patientRoute" @book="book" />
      <VisitsPage v-else-if="page==='visits' && patientReady && !patientErrors.includes('visits') && !patientErrors.includes('timeline')" :visits="visits" :timeline="timeline" />
      <ProfilePage v-else-if="page==='profile' && patientReady" />
      <p v-else-if="page==='profile'" role="status">正在验证身份…</p>
    </template>
    <AdminPage v-else :ready="adminReady" :page="adminPage" :admin-route="adminRoute" :patient-route="patientRoute" />
    <footer class="knowledge-license" aria-label="资料许可说明"><small>整理资料包含公共部门信息，依据 <a href="https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/" target="_blank" rel="noopener noreferrer">Open Government Licence v3.0</a> 使用（Contains public sector information licensed under the Open Government Licence v3.0）。补充资料：Source: MedlinePlus, National Library of Medicine。中文内容经整理，不代表来源机构认可或临床审核。</small></footer>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </main>
</template>
