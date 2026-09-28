<script setup lang="ts">
defineProps<{ displayName: string; route: (page: string) => string }>()
</script>

<template>
  <section class="content home">
    <div class="hero"><div><span>AI 智慧医院 · 智能导诊与在线挂号平台</span><h1>您好，{{ displayName }}！</h1><p>不舒服不知道该挂哪个科？用几句话描述症状，智能分诊 Agent 将结合医学知识库与医院号源为您推荐合适科室。</p><a class="light native-entry" :href="route('triage')">开始智能分诊</a><a class="outline native-entry" :href="route('booking')">在线预约挂号</a></div><div class="plus">＋</div></div>
    <h2>快捷服务</h2><div class="feature-grid"><article><i>☷</i><h3>智能预问诊</h3><p>多轮对话描述病情，AI 结合医学知识库为您提供就诊建议。</p><a :href="route('triage')">立即使用 →</a></article><article><i>▣</i><h3>预约挂号</h3><p>按科室、医生与号源时段在线预约，合理安排就诊时间。</p><a :href="route('booking')">立即使用 →</a></article><article><i>▤</i><h3>我的就诊</h3><p>集中查看预约记录与分诊摘要，随时回看。</p><a :href="route('visits')">立即使用 →</a></article><article><i>⌘</i><h3>AI 可解释性</h3><p>分诊过程提供知识依据和工具调用轨迹。</p><a :href="route('triage')">查看示例 →</a></article></div>
    <h2>就诊流程</h2><div class="flow"><span>☷<b>智能预问诊</b><small>描述不适症状</small></span><em>›</em><span>◎<b>智能分诊</b><small>推荐就诊科室</small></span><em>›</em><span>▣<b>在线挂号</b><small>选择医生时段</small></span><em>›</em><span>✓<b>到院就诊</b><small>按时到院就诊</small></span></div>
  </section>
</template>
