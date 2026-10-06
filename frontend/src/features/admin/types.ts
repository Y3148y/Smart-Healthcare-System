export interface Evidence { title: string; source: string; excerpt: string; score: number }
export interface KnowledgeDocument { id: string; title: string; body: string; chunks: number; status: string; updatedAt: string }
export interface DocumentDetail { document: KnowledgeDocument; source: string; segments: Evidence[]; indexStatus: string; indexedChunks: number; indexNote: string }
export interface Candidate { title: string; source: string; excerpt: string; lexicalRank: number; lexicalScore: number | null; semanticRank: number; semanticScore: number | null; fusedScore: number; rerankScore: number | null; kept: boolean; reason: string }
export interface RetrievalReport { retrieval: { evidence: Evidence[]; grounded: boolean; message: string }; mode: string; semanticStatus: string; rerankStatus: string; candidates: Candidate[]; elapsedMs: number }
export interface RetrievalEvent { id: string; time: string; mode: string; semanticStatus: string; rerankStatus: string; candidates: number; selected: number; elapsedMs: number }
export type KnowledgeRuntime = Record<string, string>
export const stateLabels: Record<string, string> = {
  READY: '最近检索成功', NOT_QUERIED: '尚未执行检索', NOT_CONFIGURED: '未配置', NOT_READY: '未就绪',
  INDEXED: '最近索引成功', NOT_APPROVED: '待审批', PENDING_REVIEW: '待审批', NOT_INDEXED: '尚未写入',
  INDEXED_IN_PROCESS: '本进程已验证', PARTIAL: '部分写入或验证', INDEX_UNAVAILABLE: '最近索引失败',
  SEARCH_UNAVAILABLE: '最近检索失败', OK: '最近调用成功', EMPTY: '无候选，未调用', SKIPPED: '未调用',
  NOT_CHECKED: '未检查', DEPENDENCY_BLOCKED: '依赖不可用',
  selected: '保留', below_rerank_threshold: '低于重排门槛', beyond_final_top_k: '超出最终数量',
  required_dependency_unavailable: '必需服务不可用', selected_without_rerank: '降级保留，未重排',
  beyond_candidate_or_final_top_k: '超出候选或最终数量'
}
export function label(value?: string) {
  if (value === 'HTTP_400:Arrearage') return '供应商拒绝访问：账户结算状态异常（Arrearage）'
  return value ? stateLabels[value] || value : '未检测'
}
export function sources(value: string) { return value.split('|').map(s => s.trim()).filter(s => /^https?:\/\//.test(s)) }
export function time(value: string) { return new Date(value).toLocaleString() }
export function score(value: number | null | undefined) { return value == null ? '—' : value.toFixed(4) }
