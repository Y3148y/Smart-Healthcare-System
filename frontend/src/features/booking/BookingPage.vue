<script setup lang="ts">
import type { Doctor } from '../triage/types'
defineProps<{ doctors: Doctor[]; selectedDept: string; departments: string[]; bookingRunning: boolean; route: (page: string, extra?: Record<string, string>) => string }>()
defineEmits<{ book: [doctor: Doctor] }>()
</script>

<template>
  <section class="content"><div class="section-hero"><i>▣</i><div><h1>模拟预约挂号</h1><p>当前未接入医院，以下医生和号源均为演示数据，不产生真实就诊凭证。</p></div></div><div class="filters"><b>就诊科室</b><a v-for="d in departments" :key="d" :class="{selected:selectedDept===d}" :href="route('booking',{department:d})">{{ d }}</a></div><h2>模拟可预约排班 <small>共 {{ doctors.length }} 条演示号源</small></h2><div class="doctor-grid"><article v-for="d in doctors" :key="d.id" class="doctor-card"><div class="doctor-head"><i>{{ d.name.slice(0,1) }}</i><div><h3>{{ d.name }} <label>{{ d.title }}</label></h3><p>{{ d.department }} · {{ d.period }} · {{ d.date }}</p></div></div><div class="doctor-info"><span>演示日期 <b>{{ d.date }}</b></span><span>出诊时段 <b>{{ d.period }}</b></span><span>模拟剩余号源 <b class="blue">{{ d.remaining }} / {{ d.total }}</b></span><progress :value="d.remaining" :max="d.total"></progress></div><footer><b>¥{{ d.fee }}<small>/次</small></b><button v-if="d.remaining" class="primary" :disabled="bookingRunning" @click="$emit('book',d)">{{ bookingRunning?'预约中…':'模拟预约' }}</button><span v-else class="full">已约满</span></footer></article></div></section>
</template>
