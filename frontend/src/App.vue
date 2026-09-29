<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, setToken } from './api'
import TriagePage from './features/triage/TriagePage.vue'
import HomePage from './features/home/HomePage.vue'
import BookingPage from './features/booking/BookingPage.vue'
import VisitsPage from './features/visits/VisitsPage.vue'
import AdminPage from './features/admin/AdminPage.vue'
import type { Doctor, ChatSession, TimelineEvent } from './features/triage/types'
const params=new URLSearchParams(window.location.search), demo=params.get('demo'), loginFromForm=(params.get('login')||'').trim(), initialRole=(demo==='admin'||loginFromForm.toLowerCase()==='admin')?'ADMIN':'PATIENT', isDemoEntry=Boolean(demo||loginFromForm)
const readStored=(key:string)=>{try{return localStorage.getItem(key)}catch{return null}}
const store=(key:string,value:string)=>{try{localStorage.setItem(key,value)}catch{}}
const clearStored=()=>{try{localStorage.clear()}catch{}}
// Demo routes authenticate first; rendering the app before that request succeeds caused false "login failed" states.
// A native demo-login form navigates with ?login=...; enter the matching demo role immediately,
// then obtain/refresh its API token in the background so the UI never appears to ignore a click.
const loggedIn=ref(Boolean(readStored('ai-hospital-token'))||isDemoEntry), loginName=ref('zhangsan'), password=ref('123456'), role=ref(isDemoEntry?initialRole:(readStored('ai-hospital-role')||initialRole)), displayName=ref(isDemoEntry?(initialRole==='ADMIN'?'系统管理员':loginFromForm.toLowerCase()==='lisi'?'李四':'张三'):(readStored('ai-hospital-name')||(initialRole==='ADMIN'?'系统管理员':'张三')))
const page=ref(params.get('page')||'home'), adminPage=ref(params.get('adminPage')||'dashboard'), doctors=ref<Doctor[]>([]), selectedDept=ref(params.get('department')||'全部'), visits=ref<any[]>([]), sessions=ref<ChatSession[]>([]), timeline=ref<TimelineEvent[]>([]), knowledge=ref<any[]>([]), tools=ref<any[]>([]), calls=ref<any[]>([]), dashboard=ref<any>(null), aiRuntime=ref<any>(null)
const bookingRunning=ref(false), toast=ref('')
const depts=['全部','消化内科','心血管内科','呼吸内科','骨科','神经内科','妇科']
const isAdmin=computed(()=>role.value==='ADMIN')
const nav=[['home','首页'],['triage','智能预问诊'],['booking','预约挂号'],['visits','我的就诊']]
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
async function loadPatient(){try{[doctors.value,visits.value,sessions.value,timeline.value]=await Promise.all([api<Doctor[]>('/doctors'),api<any[]>('/appointments'),api<ChatSession[]>('/triage/sessions'),api<TimelineEvent[]>('/triage/timeline')])}catch(e:any){showToast(`患者数据加载失败：${e?.message||'请检查后端服务'}`)}}
async function loadAdmin(){try{[dashboard.value,knowledge.value,tools.value,calls.value,aiRuntime.value]=await Promise.all([api('/admin/dashboard'),api<any[]>('/admin/knowledge'),api<any[]>('/admin/tools'),api<any[]>('/admin/calls'),api('/admin/ai-runtime')])}catch{}}
function patientRoute(next:string, extra:Record<string,string>={}) { return '?' + new URLSearchParams(readStored('ai-hospital-login')==='lisi'?{login:'lisi',page:next,...extra}:{demo:'patient',page:next,...extra}).toString() }
function adminRoute(next:string) { return '?' + new URLSearchParams({demo:'admin',page:'admin',adminPage:next}).toString() }
onMounted(async()=>{
  if(isDemoEntry){if(!await establishDemoSession()){loggedIn.value=false;return}loggedIn.value=true}
  else if(!loggedIn.value)return
  if(isAdmin.value){await loadAdmin();return}
  await loadPatient()
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
    const a:any=await api('/appointments',{method:'POST',body:JSON.stringify({doctorId:d.id,sessionId,idempotencyKey:crypto.randomUUID()})})
    window.location.assign(patientRoute('visits',{booked:a.registrationNo}))
  }catch(e:any){showToast(`预约未完成：${e.message}`)}finally{bookingRunning.value=false}
}
async function toggleTool(t:any){try{const next=await api<any>(`/admin/tools/${t.code}/toggle`,{method:'PATCH'});tools.value=tools.value.map(x=>x.code===t.code?next:x)}catch(e:any){showToast(e.message)}}
async function addKnowledge(){const title=prompt('知识资料标题');const body=prompt('请输入医学知识内容');if(title&&body){await api('/admin/knowledge',{method:'POST',body:JSON.stringify({title,body})});await loadAdmin();showToast('知识资料已入库并完成演示向量化')}}
function logout(){clearStored();loggedIn.value=false;role.value='PATIENT';page.value='home'}
const filteredDoctors=computed(()=>selectedDept.value==='全部'?doctors.value:doctors.value.filter(d=>d.department===selectedDept.value))
</script>

<template>
  <main v-if="!loggedIn" class="login-page"><div class="login-orb a"></div><div class="login-orb b"></div><section class="login-card"><div class="login-icon">⌁</div><h1>AI 智慧医院智能导诊系统</h1><p>智能预问诊 · 智能分诊 · 医学知识库</p><form class="login-form" method="get"><input v-model.trim="loginName" name="login" autocomplete="username" placeholder="请输入账号（zhangsan、lisi 或 admin）"><input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码"><button class="primary wide" type="submit">登 录</button></form><a class="demo-admin-link" :href="adminRoute('dashboard')">进入管理端演示 →</a><small>演示账号：zhangsan / lisi / admin，密码任意</small></section></main>
  <main v-else class="app-shell">
    <header class="topbar"><div class="brand"><span class="brand-mark">⌁</span><b>AI 智慧医院{{ isAdmin?'管理平台':'智能导诊系统' }}</b></div><template v-if="!isAdmin"><a v-for="n in nav" :key="n[0]" class="nav native-nav" :class="{active:page===n[0]}" :href="patientRoute(n[0])">{{ n[1] }}</a></template><span class="header-spacer"></span><a v-if="!isAdmin" class="user-menu native-nav" :href="adminRoute('dashboard')"><span class="avatar">{{ displayName.slice(0,1) }}</span>{{ displayName }}⌄</a><button v-else class="user-menu"><span class="avatar">{{ displayName.slice(0,1) }}</span>{{ displayName }}</button><button class="logout" @click="logout">退出</button></header>
    <template v-if="!isAdmin && page!=='admin'">
      <HomePage v-if="page==='home'" :display-name="displayName" :route="patientRoute" />
      <TriagePage v-else-if="page==='triage'" :sessions="sessions" :initial-symptom="params.get('symptom') || ''" :booking-running="bookingRunning" @book="book" @updated="loadPatient" @notify="showToast" />
      <BookingPage v-else-if="page==='booking'" :doctors="filteredDoctors" :selected-dept="selectedDept" :departments="depts" :booking-running="bookingRunning" :route="patientRoute" @book="book" />
      <VisitsPage v-else-if="page==='visits'" :visits="visits" :timeline="timeline" />
    </template>
    <AdminPage v-else :page="adminPage" :knowledge="knowledge" :tools="tools" :calls="calls" :runtime="aiRuntime" :admin-route="adminRoute" :patient-route="patientRoute" @add-knowledge="addKnowledge" @toggle-tool="toggleTool" />
    <div v-if="toast" class="toast">{{ toast }}</div>
  </main>
</template>
