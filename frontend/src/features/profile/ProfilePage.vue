<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
type Profile={displayName:string;birthDate:string|null;allergies:string;medications:string;healthBackground:string;version:number;updatedAt:string|null;source:string}
const form=reactive<Profile>({displayName:'',birthDate:null,allergies:'',medications:'',healthBackground:'',version:0,updatedAt:null,source:'PATIENT_SELF_REPORT'})
const loading=ref(true),saving=ref(false),confirmed=ref(false),error=ref(''),notice=ref(''),conflict=ref(false)
async function load(){loading.value=true;error.value='';try{Object.assign(form,await api<Profile>('/patient/profile'));confirmed.value=false;conflict.value=false;notice.value=''}catch(e:any){error.value=e.message}finally{loading.value=false}}
async function save(){if(saving.value||loading.value||!confirmed.value)return;saving.value=true;error.value='';notice.value='';try{Object.assign(form,await api<Profile>('/patient/profile',{method:'PUT',body:JSON.stringify({...form,selfReportConfirmed:confirmed.value})}));notice.value='资料已保存';confirmed.value=false;conflict.value=false}catch(e:any){error.value=e.message;conflict.value=error.value.includes('其他页面更新')}finally{saving.value=false}}
onMounted(load)
</script>
<template>
  <section class="profile-page" aria-labelledby="profile-heading">
    <header><h1 id="profile-heading">我的资料</h1><p>仅保存您本人填写的信息，不是医院病历，也不会自动生成诊断。</p></header>
    <p v-if="loading" role="status">正在加载资料…</p>
    <p v-if="error" role="alert" class="error">{{ error }} <button v-if="conflict || (!form.version && !saving)" type="button" @click="load">重新加载（放弃本页修改）</button></p>
    <p v-if="notice" role="status" class="success">{{ notice }}</p>
    <form v-if="!loading" @submit.prevent="save">
      <fieldset :disabled="saving"><legend>基础信息</legend><div class="basic-grid">
        <label>称呼<input v-model.trim="form.displayName" required maxlength="80" autocomplete="nickname"></label>
        <label>出生日期（选填，仅支持成年人）<input v-model="form.birthDate" type="date"></label>
      </div>
      <label>过敏史（选填）<textarea v-model="form.allergies" maxlength="1000" rows="3" placeholder="不清楚可留空，不会默认为无过敏"></textarea></label>
      <label>当前用药（选填）<textarea v-model="form.medications" maxlength="1000" rows="3" placeholder="填写您正在使用的药品，不提供处方建议"></textarea></label>
      <label>既往健康情况（选填）<textarea v-model="form.healthBackground" maxlength="1000" rows="3" placeholder="您希望记录的既往健康情况"></textarea></label>
      <label class="confirmation"><input v-model="confirmed" type="checkbox">我确认以上信息为本人自述，未经过医院核验</label>
      <button class="primary" type="submit" :disabled="!confirmed || conflict">{{ saving?'正在保存…':'保存资料' }}</button>
      <small v-if="form.updatedAt">最近保存：{{ new Date(form.updatedAt).toLocaleString() }} · 第 {{ form.version }} 次保存</small>
      </fieldset>
    </form>
  </section>
</template>
<style scoped>
.profile-page{max-width:960px;margin:32px auto;padding:28px;background:white;border-radius:16px;color:#20364d}.profile-page header p,small{color:#65788b;line-height:1.6}.profile-page h1{margin-top:0}fieldset{border:0;padding:0;margin-top:24px}legend{font-weight:600;padding:0;margin-bottom:18px}.basic-grid{display:grid;grid-template-columns:1fr 1fr;gap:24px}label{display:block;margin-bottom:20px;font-weight:500}input:not([type=checkbox]),textarea{display:block;box-sizing:border-box;width:100%;margin-top:8px;border:1px solid #bdccdb;border-radius:8px;padding:12px;font:inherit;color:inherit;background:#fff}textarea{resize:vertical}input:focus-visible,textarea:focus-visible,button:focus-visible{outline:3px solid #91c5ff;outline-offset:2px}.confirmation{display:flex;align-items:center;gap:10px;font-size:14px}button:disabled{opacity:.55;cursor:not-allowed}.error{color:#a52a2a;background:#fff2f2;padding:12px}.success{color:#17784f;background:#edf8f2;padding:12px}small{display:block;margin-top:14px}@media(max-width:700px){.profile-page{margin:16px;padding:20px}.basic-grid{grid-template-columns:1fr;gap:0}}
</style>
