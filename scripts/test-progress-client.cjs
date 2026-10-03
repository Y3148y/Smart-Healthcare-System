// Synthetic SSE transport fixtures only; does not simulate medical or model answers.
const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path')
const ts=require(path.resolve('.codex-rag-ui/node_modules/typescript'))
const exportsObject={}
new Function('exports',ts.transpileModule(fs.readFileSync('frontend/src/api.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText)(exportsObject)
function fixture(text){const bytes=new TextEncoder().encode(text);let i=0;global.fetch=async()=>new Response(new ReadableStream({pull(controller){if(i>=bytes.length){controller.close();return}controller.enqueue(bytes.slice(i,i+1));i++}}),{headers:{'Content-Type':'text/event-stream'}})}
;(async()=>{
 const stages=[]
 fixture('event:status\r\ndata:{"stage":"SAFETY_CHECK"}\r\n\r\nevent:result\r\ndata:{"text":"中文保留"}\r\n\r\n')
 const result=await exportsObject.streamResult('/test',{},s=>stages.push(s));assert.equal(result.text,'中文保留');assert.deepEqual(stages,['SAFETY_CHECK'])
 fixture('event:failure\ndata:{"message":"测试错误"}\n\n');await assert.rejects(()=>exportsObject.streamResult('/test',{},()=>{}),/测试错误/)
 fixture('event:status\ndata:{"stage":"ACCEPTED"}\n\n');await assert.rejects(()=>exportsObject.streamResult('/test',{},()=>{}),/连接中断/)
 fixture('event:result\ndata:invalid-json\n\n');await assert.rejects(()=>exportsObject.streamResult('/test',{},()=>{}),SyntaxError)
 console.log('PASS: fragmented UTF-8 and CRLF, failure event, incomplete stream, invalid JSON')
})().catch(e=>{console.error(e);process.exit(1)})
