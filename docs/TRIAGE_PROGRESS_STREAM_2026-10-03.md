# 分诊阶段状态推送（第一步）

## 实现和真实边界

本批只实现阶段状态流，不是LLM token流，不拆已生成文字制造打字效果。正文沿用原完整输出校验、药品句过滤与落库后一次性返回。未降低模型总耗时，不把首个状态到达时间当模型首token延迟。

新增 `POST /api/triage/sessions/{id}/turns/stream`，Bearer令牌放请求头，不放URL。保留原普通JSON接口。授权/本人会话/输入校验在执行前完成；事件为`status`（实际阶段）、`result`（完整已保存Conversation）、`failure`（可读错误）。前端不在流失败后自动重发请求，而是沿用原会话核对与草稿恢复逻辑。

阶段来自执行入口：SAFETY_CHECK、KNOWLEDGE_RETRIEVAL、ANSWER_GENERATION、SCHEDULE_LOOKUP、SAVING。ACCEPTED表示任务已接收。生成状态只表示进入生成流程，demo或模型降级仍需查看最终provenance；不声称每次一定调用外部LLM。结构化决策与解说可能分别进入生成阶段；阶段不一定单向排列。危急规则路径不发送检索/生成/号源阶段。

SSE专用执行器上限4，无等待队列；繁忙503，同会话SSE处理中再次提交409，不追加用户消息。此互斥不覆盖旧JSON接口，也不解决多实例并发。连接超时120秒；客户端断开不重放、不主动取消已经开始的模型调用，后端继续保存结果，客户端稍后从历史恢复。尚无心跳、自动续接、请求幂等键、完整优雅停机或模型token流。

## 文件和函数

- `TriageProgress`：阶段枚举，无医疗内容。
- `TriageEngine`：可选进度回调重载，旧接口保留。
- `TriageConversationService.send`：实际安全检查/保存节点与回调传递；普通调用继续使用旧Engine接口。
- `RuleBasedTriageEngine.clarificationPrompt/triage`：实际检索、生成、号源节点。不改临床规则或输出校验。
- `TriageStreamController.send/event`：角色和会话校验、有限并发、SSE写出、断开隔离、错误处理。
- `frontend/src/api.ts`：复用鉴权/仅401认证刷新；fetch读取SSE，UTF-8流式解码、跨包分隔处理、服务失败/缺result断流拒绝、缓冲大小上限。
- `TriagePage.startTriage`：使用SSE状态，取消按秒猜测阶段；结果返回前不展示新预约建议。输入清空及失败后历史核对逻辑保留。
- `TriageProgressTest`：阶段顺序、危急绕过生成、本人权限、SSE结果。
- `test-progress-client.cjs`：仅传输层合成样本，逐字节中文/CRLF、failure、缺result、非法JSON。
- `test-progress-live.cjs`：真实模型、处理中重复请求、主动断开后恢复、浏览器输入清空/状态/完整结果。

## 验证

2026-10-03，隔离`.codex-stream-tests`，JDK17、无AI环境变量，`mvn clean test -q -DforkCount=0`：134项，133通过、1外部测试跳过、零失败/错误。初次新增重载导致既有mock测试返回空，已修普通调用兼容后全量重跑，不删除或跳过原用例。副本包含既有未提交安全修改，不纳入此任务commit。

前端隔离副本`npm run build`通过；`node scripts/test-progress-client.cjs`4类解析测试通过。当前8081已更新，原数据库、glm-5.3及百炼检索配置保留。通过5188代理执行真实检查：首status 33ms（本机单次测量）；主动断开后会话`4ed51d6d-0ab5-4c72-bdca-2240489e23df`保存一条USER和一条LIVE回答；期间同会话重复提交409。浏览器收到检索、号源、生成、保存阶段，最终展示分诊卡，输入发送后清空；正文原校验路径未绕过。

合成lisi会话保留，无模拟挂号或扣号源。独立复核待完成。浏览器副本仍包含之前未提交的提示压缩，不能说本批已验收该历史改动；本任务只提交状态逻辑差异。
