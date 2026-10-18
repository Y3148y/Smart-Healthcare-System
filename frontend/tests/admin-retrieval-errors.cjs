const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'features', 'admin', 'types.ts'), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText
const context = { exports: {} }
vm.runInNewContext(compiled, context, { filename: 'admin/types.ts' })
const { label } = context.exports

test('Elasticsearch operational failures have safe actionable admin labels', () => {
  assert.equal(label('ELASTICSEARCH_CONNECTION_REFUSED'), 'ES 连接被拒绝：检查容器和端口')
  assert.equal(label('ELASTICSEARCH_CONNECT_TIMEOUT'), 'ES 建连超时')
  assert.equal(label('ELASTICSEARCH_REQUEST_TIMEOUT'), 'ES 请求超时')
  assert.equal(label('ELASTICSEARCH_HTTP_503'), 'ES 返回 HTTP 503')
  assert.equal(label('ELASTICSEARCH_HTTP_200'), 'ELASTICSEARCH_HTTP_200')
  assert.equal(label('ELASTICSEARCH_UNAVAILABLE'), 'ES 不可用')
})

test('semantic dependency labels explain failures without inferring depleted quota', () => {
  assert.equal(label('EMBEDDING_HTTP_429'), 'Embedding 服务返回 HTTP 429（可能限流；不能仅凭此判断额度耗尽）')
  assert.equal(label('EMBEDDING_HTTP_400:InvalidParameter:model'), 'Embedding 服务返回 HTTP 400')
  assert.equal(label('EMBEDDING_RESPONSE_VECTOR_COUNT_MISMATCH'), 'Embedding返回向量数量与请求不符')
  assert.equal(label('EMBEDDING_INVALID_RESPONSE'), 'Embedding 返回格式无效')
  assert.equal(label('EMBEDDING_REQUEST_TIMEOUT'), 'Embedding 请求超时')
  assert.equal(label('QDRANT_COLLECTION_DIMENSION_MISMATCH'), 'Qdrant集合向量维度或距离不匹配：需单独迁移到新集合')
  assert.equal(label('QDRANT_UPSERT_CONNECTION_REFUSED'), 'Qdrant 连接被拒绝：检查服务和端口')
  assert.equal(label('QDRANT_SEARCH_INVALID_RESPONSE'), 'Qdrant 返回格式无效')
  assert.equal(label('QDRANT_HTTP_503'), 'Qdrant 服务返回 HTTP 503')
  assert.equal(label('EMBEDDING_HTTP_503:private provider detail'), 'Embedding 服务返回 HTTP 503')
})
