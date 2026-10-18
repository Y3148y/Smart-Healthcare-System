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
