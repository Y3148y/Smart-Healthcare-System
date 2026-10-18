export interface Evidence { title: string; source: string; excerpt: string; score: number }
export interface KnowledgeDocument { id: string; title: string; body: string; chunks: number; status: string; updatedAt: string }
export interface DocumentDetail { document: KnowledgeDocument; source: string; segments: Evidence[]; indexStatus: string; indexedChunks: number; indexNote: string }
export interface Candidate { title: string; source: string; excerpt: string; lexicalRank: number; lexicalScore: number | null; semanticRank: number; semanticScore: number | null; fusedScore: number; rerankScore: number | null; kept: boolean; reason: string }
export interface RetrievalReport { retrieval: { evidence: Evidence[]; grounded: boolean; message: string }; mode: string; lexicalStatus?: string; semanticStatus: string; rerankStatus: string; candidates: Candidate[]; elapsedMs: number }
export interface RetrievalEvent { id: string; time: string; mode: string; lexicalStatus?: string; semanticStatus: string; rerankStatus: string; candidates: number; selected: number; elapsedMs: number }
export type KnowledgeRuntime = Record<string, string>
export const stateLabels: Record<string, string> = {
  KNOWLEDGE_STORE_UNAVAILABLE: '知识状态无法核验，未采用旧缓存',
  KNOWLEDGE_SNAPSHOT_CHANGED: '审批状态已变化，本轮旧候选未采用',
  MEMORY_BM25_READY: '内存 BM25 成功', ELASTICSEARCH_READY: 'ES BM25 成功', ELASTICSEARCH_EMPTY_CORPUS: 'ES：无批准语料',
  ELASTICSEARCH_UNAVAILABLE: 'ES 不可用', ELASTICSEARCH_INTERRUPTED: 'ES 请求中断',
  ELASTICSEARCH_INVALID_RESPONSE: 'ES 返回身份校验失败', ELASTICSEARCH_PARTIAL_RESULT: 'ES 返回不完整，未采用',
  READY: '最近检索成功', NOT_QUERIED: '尚未执行检索', NOT_CONFIGURED: '未配置', NOT_READY: '未就绪',
  INDEXED: '最近索引成功', NOT_APPROVED: '待审批', PENDING_REVIEW: '待审批', NOT_INDEXED: '尚未写入',
  INDEXED_IN_PROCESS: '本进程已验证', PARTIAL: '部分写入或验证', INDEX_UNAVAILABLE: '最近索引失败',
  QDRANT_COLLECTION_DIMENSION_MISMATCH: 'Qdrant集合向量维度或距离不匹配：需单独迁移到新集合',
  QDRANT_INVALID_INDEX_CONFIGURATION: 'Qdrant索引参数无效',
  EMBEDDING_RESPONSE_VECTOR_COUNT_MISMATCH: 'Embedding返回向量数量与请求不符',
  EMBEDDING_RESPONSE_INVALID_VECTOR_VALUE: 'Embedding返回了无效向量值',
  EMBEDDING_RESPONSE_EMPTY_VECTOR: 'Embedding返回了空向量',
  EMBEDDING_RESPONSE_INVALID_INDEX: 'Embedding返回了无效序号',
  EMBEDDING_RESPONSE_INCOMPLETE: 'Embedding响应缺少向量',
  EMBEDDING_RESPONSE_INCONSISTENT_DIMENSIONS: 'Embedding返回的向量维度不一致',
  SEARCH_UNAVAILABLE: '最近检索失败', OK: '最近调用成功', EMPTY: '无候选，未调用', SKIPPED: '未调用',
  NOT_CHECKED: '未检查', DEPENDENCY_BLOCKED: '依赖不可用',
  selected: '保留', below_rerank_threshold: '低于重排门槛', beyond_final_top_k: '超出最终数量',
  required_dependency_unavailable: '必需服务不可用', selected_without_rerank: '降级保留，未重排',
  beyond_candidate_or_final_top_k: '超出候选或最终数量'
}
export function label(value?: string) {
  if (value === 'HTTP_400:Arrearage') return '供应商拒绝访问：账户结算状态异常（Arrearage）'
  if (value === 'ELASTICSEARCH_CONNECTION_REFUSED') return 'ES 连接被拒绝：检查容器和端口'
  if (value === 'ELASTICSEARCH_CONNECT_TIMEOUT') return 'ES 建连超时'
  if (value === 'ELASTICSEARCH_REQUEST_TIMEOUT') return 'ES 请求超时'
  const esHttpStatus = value?.match(/^ELASTICSEARCH_HTTP_([45]\d\d)$/)
  if (esHttpStatus) return `ES 返回 HTTP ${esHttpStatus[1]}`
  const semanticFailure = value?.match(/^(EMBEDDING|QDRANT_(?:LOOKUP|COLLECTION|UPSERT|SEARCH))_(CONNECT_TIMEOUT|REQUEST_TIMEOUT|CONNECTION_REFUSED|TRANSPORT_UNAVAILABLE|INVALID_RESPONSE|INTERRUPTED)$/)
  if (semanticFailure) {
    const service = semanticFailure[1] === 'EMBEDDING' ? 'Embedding' : 'Qdrant'
    const reasons: Record<string, string> = {
      CONNECT_TIMEOUT: '建连超时', REQUEST_TIMEOUT: '请求超时', CONNECTION_REFUSED: '连接被拒绝：检查服务和端口',
      TRANSPORT_UNAVAILABLE: '网络传输失败', INVALID_RESPONSE: '返回格式无效', INTERRUPTED: '请求被中断'
    }
    return `${service} ${reasons[semanticFailure[2]]}`
  }
  const embeddingHttpStatus = value?.match(/^EMBEDDING_HTTP_([45]\d\d)(?::.*)?$/)
  if (embeddingHttpStatus) return embeddingHttpStatus[1] === '429'
    ? 'Embedding 服务返回 HTTP 429（可能限流；不能仅凭此判断额度耗尽）'
    : `Embedding 服务返回 HTTP ${embeddingHttpStatus[1]}`
  const qdrantHttpStatus = value?.match(/^QDRANT_HTTP_([45]\d\d)$/)
  if (qdrantHttpStatus) return `Qdrant 服务返回 HTTP ${qdrantHttpStatus[1]}`
  return value ? stateLabels[value] || value : '未检测'
}
export function sources(value: string) { return value.split('|').map(s => s.trim()).filter(s => /^https?:\/\//.test(s)) }
export function time(value: string) { return new Date(value).toLocaleString() }
export function score(value: number | null | undefined) { return value == null ? '—' : value.toFixed(4) }
