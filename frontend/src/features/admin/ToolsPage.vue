<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import { toolCenter, toolParameter } from './toolCenter'

const { tools, loading, busy, error, notice, loaded, needsRefresh, execution, load, toggle, run } = toolCenter(api)
const selected = ref(''), input = ref('')
const selectedTool = computed(() => tools.value.find(tool => tool.code === selected.value))
const parameter = computed(() => toolParameter(selected.value))
function select(code: string) { selected.value = code; input.value = ''; execution.value = null; notice.value = ''; error.value = '' }
onMounted(load)
</script>

<template>
  <div class="admin-title"><div><h2>Agent 工具中心</h2><p>此处由管理员直接试运行本地工具，不是模型自主选择工具，也不是外部 MCP 客户端调用。</p></div><button type="button" class="mini" :disabled="loading || busy" @click="load">{{ loading ? '正在加载…' : '刷新工具列表' }}</button></div>
  <p v-if="error" role="alert" class="admin-tool-error">{{ error }}</p>
  <p v-if="notice" role="status">{{ notice }}</p>
  <p v-if="!loaded && loading" role="status">正在加载工具…</p>
  <p>启停状态由后台保存；停用可选工具不能取消工作流的强制安全检查。真实医院接口尚未接入。</p>
  <div v-if="loaded" class="tool-pool"><b>当前启用的工具：</b><span v-for="tool in tools.filter(t => t.enabled)" :key="tool.code">{{ tool.code }}</span><span v-if="!tools.some(t => t.enabled)">无</span></div>
  <p v-if="loaded && !tools.length" class="admin-empty">当前没有已注册工具。</p>
  <div v-else-if="loaded" class="table-card admin-scroll"><table><thead><tr><th>工具编码</th><th>工具名称</th><th>说明</th><th>启用状态</th><th>操作</th></tr></thead><tbody><tr v-for="tool in tools" :key="tool.code"><td>{{ tool.code }}</td><td>{{ tool.name }}</td><td>{{ tool.description }}</td><td><button type="button" class="switch" :class="{on:tool.enabled}" :aria-pressed="tool.enabled" :aria-label="'切换 '+tool.name" :disabled="busy || loading || needsRefresh" @click="toggle(tool.code)"><i></i></button></td><td><button type="button" class="mini" :disabled="busy || loading || needsRefresh || !tool.enabled || !toolParameter(tool.code)" @click="select(tool.code)">试运行</button></td></tr></tbody></table></div>
  <section v-if="selectedTool" class="table-card admin-tool-trial" aria-label="工具试运行">
    <h3>{{ selectedTool.name }}</h3>
    <form @submit.prevent="run(selected, input)"><label>{{ parameter === 'department' ? '科室名称' : '检索问题或症状' }}<input v-model="input" required maxlength="2200" :disabled="busy || loading" /></label><button type="submit" class="primary" :disabled="busy || loading || needsRefresh || !selectedTool.enabled">{{ busy ? '正在执行…' : '执行只读工具' }}</button></form>
    <p>请只输入合成测试内容。执行成功不代表找到医学依据、完成模型回答或产生预约。</p>
    <details v-if="execution" open><summary>本次实际返回结果<span v-if="execution.trace.elapsedMs != null"> · {{ execution.trace.elapsedMs }}ms</span></summary><pre>{{ JSON.stringify(execution.data, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.admin-tool-error{color:#b42318}.admin-tool-trial{padding:20px;margin-top:20px}.admin-tool-trial form{display:flex;align-items:end;gap:16px;flex-wrap:wrap}.admin-tool-trial label{display:grid;gap:8px;flex:1;min-width:180px}.admin-tool-trial input{padding:10px;border:1px solid #d8e7f5;border-radius:6px}.admin-tool-trial pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto}
</style>
