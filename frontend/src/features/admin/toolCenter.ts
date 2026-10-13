import { ref } from 'vue'

export type ManagedTool = { code: string; name: string; description: string; enabled: boolean }
type Request = <T>(path: string, init?: RequestInit) => Promise<T>
type Execution = { data: unknown; trace: { success: boolean; outcome: string; error?: string; elapsedMs?: number } }

// Tool protocol parameters, not symptom-to-department rules. Unknown tools are not trialled.
export function toolParameter(code: string): 'query' | 'department' | null {
  if (['department_search', 'doctor_schedule_search'].includes(code)) return 'department'
  if (['symptom_tag_search', 'medical_knowledge_retrieve'].includes(code)) return 'query'
  return null
}

export function toolCenter(request: Request) {
  const tools = ref<ManagedTool[]>([]), loading = ref(false), busy = ref(false)
  const error = ref(''), notice = ref(''), loaded = ref(false), needsRefresh = ref(false)
  const execution = ref<Execution | null>(null)
  const failure = (value: unknown) => value instanceof Error ? value.message : '请求失败'
  async function load() {
    if (loading.value || busy.value) return
    loading.value = true; error.value = ''
    try {
      tools.value = await request<ManagedTool[]>('/admin/tools')
      loaded.value = true; needsRefresh.value = false
    } catch (e) { needsRefresh.value = true; error.value = `工具列表加载失败：${failure(e)}` }
    finally { loading.value = false }
  }
  async function toggle(code: string) {
    if (busy.value || loading.value || !loaded.value || needsRefresh.value) return
    const tool = tools.value.find(t => t.code === code)
    if (!tool) return
    busy.value = true; error.value = ''; notice.value = ''; execution.value = null
    try {
      const updated = await request<ManagedTool>(`/admin/tools/${encodeURIComponent(code)}/toggle`, { method: 'PATCH' })
      tools.value = tools.value.map(t => t.code === code ? updated : t)
      notice.value = `${updated.name}已${updated.enabled ? '启用' : '停用'}`
    } catch (e) {
      needsRefresh.value = true
      error.value = `状态变更结果待确认：${failure(e)}。请刷新核对后再操作，不会自动重试。`
    } finally { busy.value = false }
  }
  async function run(code: string, input: string) {
    if (busy.value || loading.value || !loaded.value || needsRefresh.value) return
    const tool = tools.value.find(t => t.code === code), parameter = toolParameter(code)
    if (!tool?.enabled || !parameter) { error.value = '该工具未启用或不支持试运行'; return }
    const value = input.trim()
    if (!value || value.length > 2200) { error.value = '参数应为 1 至 2200 字'; return }
    busy.value = true; error.value = ''; notice.value = ''; execution.value = null
    try {
      const result = await request<Execution>(`/admin/tools/${encodeURIComponent(code)}/run`, {
        method: 'POST', body: JSON.stringify({ [parameter]: value })
      })
      execution.value = result
      if (result.trace.success) notice.value = `工具已执行：${result.trace.outcome}`
      else error.value = `工具执行失败：${result.trace.error || result.trace.outcome}`
    } catch (e) { error.value = `工具请求失败：${failure(e)}` }
    finally { busy.value = false }
  }
  return { tools, loading, busy, error, notice, loaded, needsRefresh, execution, load, toggle, run }
}
