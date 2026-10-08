const test=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')

test('HTML declares UTF-8 before localized title and Vue entrypoint',()=>{
  const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8')
  assert.match(html,/^<!doctype html>/i)
  assert.match(html,/<html\s+lang="zh-CN">/i)
  assert.ok(html.indexOf('<meta charset="UTF-8">')>=0)
  assert.ok(html.indexOf('<meta charset="UTF-8">')<html.indexOf('<title>'))
  assert.match(html,/<title>AI 智能导诊预约系统<\/title>/)
})
