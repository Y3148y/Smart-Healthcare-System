<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../api'
type Department={id:string;name:string;enabled:boolean}
type Doctor={id:string;name:string;title:string;departmentId:string;department:string;enabled:boolean;date:string;period:string;total:number;remaining:number;fee:number}
const departments=ref<Department[]>([]),doctors=ref<Doctor[]>([]),error=ref(''),notice=ref(''),busy=ref(false)
const department=ref({id:'',name:'',enabled:true})
const tomorrow=new Date();tomorrow.setDate(tomorrow.getDate()+1)
const blank=()=>({id:'',name:'',title:'',departmentId:'',enabled:true,date:tomorrow.toLocaleDateString('sv-SE'),period:'上午',total:20,fee:0})
const doctor=ref(blank())
async function load(){[departments.value,doctors.value]=await Promise.all([api<Department[]>('/admin/catalog/departments'),api<Doctor[]>('/admin/catalog/doctors')])}
async function execute(action:()=>Promise<unknown>,message:string){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await action();await load();notice.value=message}catch(e:any){error.value=e.message||'操作失败，请重试'}finally{busy.value=false}}
function saveDepartment(){const d={...department.value};return execute(async()=>{await api(`/admin/catalog/departments${d.id?'/'+d.id:''}`,{method:d.id?'PUT':'POST',body:JSON.stringify(d)});department.value={id:'',name:'',enabled:true}},'科室已保存，患者目录同步生效。')}
function saveDoctor(){const d={...doctor.value};return execute(async()=>{await api(`/admin/catalog/doctors${d.id?'/'+d.id:''}`,{method:d.id?'PUT':'POST',body:JSON.stringify(d)});doctor.value=blank()},'医生与模拟号源已保存。')}
function toggleDepartment(d:Department){return execute(()=>api(`/admin/catalog/departments/${d.id}`,{method:'PUT',body:JSON.stringify({...d,enabled:!d.enabled})}),'科室状态已更新，历史预约保留。')}
function toggleDoctor(d:Doctor){return execute(()=>api(`/admin/catalog/doctors/${d.id}`,{method:'PUT',body:JSON.stringify({...d,enabled:!d.enabled})}),'医生状态已更新，历史预约保留。')}
onMounted(()=>execute(load,''))
</script>
<template>
  <div class="admin-title"><div><h2>科室、医生与模拟号源</h2><p>目录持久化维护，当前仍是模拟医院数据。每位医生只维护一个当前日期/时段，不是完整排班。</p></div></div>
  <p v-if="error" role="alert" class="catalog-error">{{error}}</p><p v-if="notice" role="status">{{notice}}</p>
  <p>停用会停止新增推荐与预约，不删除历史记录。同一日期的时段固定；未过期且已有预约的号源不能改日期；容量不能低于已预约数量。</p>
  <section class="table-card catalog-section"><h3>科室管理</h3>
    <form class="catalog-form" @submit.prevent="saveDepartment"><label>科室名称<input v-model.trim="department.name" required maxlength="80"></label><label class="admin-check"><input type="checkbox" v-model="department.enabled">启用科室</label><button class="primary" :disabled="busy">{{department.id?'保存科室':'新增科室'}}</button><button type="button" class="mini" @click="department={id:'',name:'',enabled:true}">清空科室表单</button></form>
    <table><thead><tr><th>名称</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="d in departments" :key="d.id"><td>{{d.name}}</td><td>{{d.enabled?'启用':'停用'}}</td><td><button class="mini" :disabled="busy" @click="department={...d}">编辑科室</button> <button class="mini" :disabled="busy" @click="toggleDepartment(d)">{{d.enabled?'停用科室':'启用科室'}}</button></td></tr></tbody></table>
  </section>
  <section class="table-card catalog-section"><h3>医生与当前号源</h3>
    <form class="catalog-form" @submit.prevent="saveDoctor">
      <label>医生姓名<input v-model.trim="doctor.name" required maxlength="80"></label><label>职称<input v-model.trim="doctor.title" required maxlength="80"></label>
      <label>所属科室<select aria-label="所属科室" v-model="doctor.departmentId" required><option disabled value="">请选择</option><option v-for="d in departments" :key="d.id" :value="d.id">{{d.name}}{{d.enabled?'':'（停用）'}}</option></select></label>
      <label>号源日期<input type="date" v-model="doctor.date" required></label><label>时段<select v-model="doctor.period"><option>上午</option><option>下午</option><option>全天</option></select></label>
      <label>总容量<input type="number" v-model.number="doctor.total" min="0" max="1000" step="1" required></label><label>模拟费用（元）<input type="number" v-model.number="doctor.fee" min="0" max="100000" step="1" required></label>
      <label class="admin-check"><input type="checkbox" v-model="doctor.enabled">启用医生</label><button class="primary" :disabled="busy">{{doctor.id?'保存医生':'新增医生'}}</button><button type="button" class="mini" @click="doctor=blank()">清空医生表单</button>
    </form>
    <table><thead><tr><th>医生 / 职称</th><th>科室</th><th>当前日期 / 时段</th><th>可用 / 总量</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="d in doctors" :key="d.id"><td>{{d.name}} · {{d.title}}</td><td>{{d.department}}</td><td>{{d.date}} · {{d.period}}</td><td>{{d.remaining}} / {{d.total}}</td><td>{{d.enabled?'启用':'停用'}}</td><td><button class="mini" :disabled="busy" @click="doctor={...d}">编辑医生</button> <button class="mini" :disabled="busy" @click="toggleDoctor(d)">{{d.enabled?'停用医生':'启用医生'}}</button></td></tr></tbody></table>
  </section>
</template>
<style scoped>
.catalog-section{padding:20px;margin-top:20px;overflow:auto}.catalog-form{display:flex;flex-wrap:wrap;align-items:end;gap:16px;margin-bottom:24px}.catalog-form label{display:flex;flex-direction:column;gap:7px;min-width:140px}.catalog-form input,.catalog-form select{padding:10px;border:1px solid #d8e7f5;border-radius:6px;max-width:240px}.catalog-form .admin-check{flex-direction:row;align-items:center}.catalog-error{color:#b42318;background:#fff1ee;padding:12px;border-radius:8px}
</style>
