<script setup lang="ts">
import KnowledgePage from './KnowledgePage.vue'
import CatalogPage from './CatalogPage.vue'
import RetrievalDebugPage from './RetrievalDebugPage.vue'
import RuntimeObservePage from './RuntimeObservePage.vue'
import './admin.css'
import ToolsPage from './ToolsPage.vue'
import HumanReviewsPage from './HumanReviewsPage.vue'
withDefaults(defineProps<{ ready?: boolean; page: string; adminRoute: (page: string) => string; patientRoute: (page: string) => string }>(), {ready:true})
</script>

<template>
  <section class="admin-shell">
    <aside class="admin-side">
      <b>⌁ AI 智能导诊管理平台</b>
      <a :class="{on:page==='dashboard'}" :href="adminRoute('dashboard')">⌂ 系统首页</a>
      <p>导诊业务管理</p>
      <a :class="{on:page==='catalog'}" :aria-current="page==='catalog'?'page':undefined" :href="adminRoute('catalog')">▦ 科室 / 医生 / 号源管理</a>
      <a :class="{on:page==='reviews'}" :href="adminRoute('reviews')">☏ 人工导诊申请</a>
      <p>AI 能力管理</p>
      <a :class="{on:page==='knowledge'}" :href="adminRoute('knowledge')">▤ 医学知识库</a>
      <a :class="{on:page==='retrieval'}" :href="adminRoute('retrieval')">⌕ 检索调试</a>
      <a :class="{on:page==='tools'}" :href="adminRoute('tools')">⚙ Agent 工具中心</a>
      <a :class="{on:page==='observe'}" :href="adminRoute('observe')">◉ AI 运行观测</a>
      <a :href="patientRoute('home')">← 返回患者端</a>
    </aside>
    <div v-if="!ready" class="admin-content" role="status">正在加载管理会话…</div><div v-else class="admin-content">
    <CatalogPage v-if="page==='catalog'" />
    <KnowledgePage v-else-if="page==='knowledge'" />
    <RetrievalDebugPage v-else-if="page==='retrieval'" />
    <RuntimeObservePage v-else-if="page==='observe'" />
    <div v-else-if="page==='dashboard'"><div class="admin-hero"><span>AI 智能导诊预约系统</span><h1>您好，系统管理员 <label>管理员</label></h1><p>本机演示版：多轮预问诊、风险规则、知识检索、模拟挂号及人工复核申请。</p><b>预问诊　 危险信号拦截　 知识依据　 工具调用记录</b></div><h2>功能导航</h2><div class="admin-cards"><article><i>▤</i><h3>医学知识库</h3><p>核对来源、查看原文和切片、审批上传资料及同步向量索引。</p><a class="mini" :href="adminRoute('knowledge')">管理资料</a></article><article><i>⌕</i><h3>检索调试</h3><p>逐项查看 BM25、向量召回、RRF 融合与重排候选。</p><a class="mini" :href="adminRoute('retrieval')">调试检索</a></article><article><i>⚙</i><h3>工具中心</h3><p>本地医院演示数据通过统一工具边界执行，提供 MCP 协议子集入口。</p><a class="mini" :href="adminRoute('tools')">管理工具</a></article><article><i>◉</i><h3>运行观测</h3><p>查看模型配置、最近检索状态、真实调用耗时与工具错误。</p><a class="mini" :href="adminRoute('observe')">查看记录</a></article></div></div>
    <ToolsPage v-else-if="page==='tools'" />
      <HumanReviewsPage v-else-if="page==='reviews'" />
    <div v-else class="admin-empty">该管理页面不存在，请从左侧导航选择功能。</div>
  </div></section>
</template>
