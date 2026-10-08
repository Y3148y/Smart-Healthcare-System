const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = relative => fs.readFileSync(path.join(__dirname, '..', 'src', relative), 'utf8')

test('knowledge detail action loads an admin detail endpoint and brings the result into view', () => {
  const page = read('features/admin/KnowledgePage.vue')
  const controller = fs.readFileSync(path.join(__dirname, '..', '..', 'backend', 'src/main/java/com/aihospital/knowledge/api/AdminKnowledgeController.java'), 'utf8')
  assert.match(page, /\/admin\/knowledge\/\$\{encodeURIComponent\(id\)\}\/details/)
  assert.match(controller, /@GetMapping\("\/{id}\/details"\)/)
  assert.match(page, /detailPanel\.value\?\.scrollIntoView/)
  assert.match(page, /d\.status==='PENDING_REVIEW' \? '查看并审核'/)
  assert.match(page, /核对后审批通过/)
  assert.match(page, /\/admin\/knowledge\/\$\{encodeURIComponent\(id\)\}\/approve/)
  assert.match(controller, /@PostMapping\("\/{id}\/approve"\)/)
})

test('runtime call-order action expands details directly inside its selected table row', () => {
  const page = read('features/admin/RuntimeObservePage.vue')
  assert.match(page, /:aria-expanded="selected\?\.id===c\.id"/)
  assert.match(page, /selected=selected\?\.id===c\.id\?null:c/)
  assert.match(page, /v-if="selected\?\.id===c\.id"/)
  assert.match(page, /这条记录没有保存工具轨迹/)
  assert.match(page, /admin-call-order/)
})
