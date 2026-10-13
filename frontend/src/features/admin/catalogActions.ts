import { ref } from 'vue'

/** Keep a successful write distinct from a failed read-back; never replay a mutation. */
export function catalogActions(load: () => Promise<void>) {
  const busy = ref(false), ready = ref(false), error = ref(''), notice = ref('')
  const message = (e: unknown) => e instanceof Error ? e.message : '请求失败'
  async function refresh() {
    if (busy.value) return
    busy.value = true; error.value = ''
    try { await load(); ready.value = true }
    catch (e) { ready.value = false; error.value = `目录加载失败：${message(e)}。请刷新后再操作。` }
    finally { busy.value = false }
  }
  async function execute(action: () => Promise<unknown>, success: string) {
    if (busy.value || !ready.value) return
    busy.value = true; error.value = ''; notice.value = ''
    try {
      await action()
      notice.value = success
    } catch (e) {
      ready.value = false; busy.value = false
      error.value = `操作结果待核对：${message(e)}。请刷新目录确认，不会自动重试。`
      return
    }
    try { await load(); ready.value = true }
    catch (e) {
      ready.value = false
      error.value = `操作已成功，但目录刷新失败：${message(e)}。请刷新查看最新状态。`
    } finally { busy.value = false }
  }
  return { busy, ready, error, notice, refresh, execute }
}
