<script setup lang="ts">
import type { TimelineEvent } from '../triage/types'
defineProps<{ visits: any[]; timeline: TimelineEvent[] }>()
</script>

<template>
  <section class="content">
    <div class="section-hero"><i>▤</i><div><h1>我的就诊记录</h1><p>展示当前账号的模拟预约与本人自述症状时间线；它不是正式病历。</p></div></div>
    <h2>模拟预约记录</h2>
    <div class="table-card"><table><thead><tr><th>模拟预约单号</th><th>科室 / 医生</th><th>出诊日期</th><th>时段</th><th>状态</th><th>关联分诊</th></tr></thead><tbody><tr v-for="v in visits" :key="v.id"><td class="blue">{{ v.registrationNo }}</td><td>{{ v.doctor.department }} · {{ v.doctor.name }}</td><td>{{ v.doctor.date }}</td><td>{{ v.doctor.period }}</td><td><label class="status">{{ v.status }}</label></td><td>{{ v.triageSessionId }}</td></tr><tr v-if="!visits.length"><td colspan="6" class="empty">尚无模拟预约记录</td></tr></tbody></table></div>
    <h2>症状时间线 <small>患者自述原文，非医生病历</small></h2>
    <div class="timeline-list"><article v-for="event in timeline" :key="event.messageId"><small>{{ new Date(event.occurredAt).toLocaleString() }} · {{ event.source }}</small><b>{{ event.sessionTitle }}</b><p>{{ event.content }}</p></article><div v-if="!timeline.length" class="empty">暂无症状记录，开始一次预问诊后会显示在这里。</div></div>
  </section>
</template>
