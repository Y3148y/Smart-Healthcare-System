# P0 返工讲解页（2026-09-29）

定位：本机辅助预问诊演示，不是医院上线交付。原 P0 测试全绿并不等于四项验收已完成。本轮只补上下文、阈值、增量索引和文档；保留原有安全规则、MCP 入口、人工复核、日志持久化等改动。

## 文件＋函数级改动与原因

| 文件（`backend/src/main/java/com/aihospital/` 为共同前缀；前端另列） | 函数/内容及原因 |
| --- | --- |
| `triage/domain/NarrationModel.java`、`triage/domain/TriageEngine.java` | `Turn`、`guide/explain`、`clarificationPrompt/triage` 增加消息列表参数，让会话历史跨过领域接口。 |
| `triage/application/TriageConversationService.java` | `send` 从已存 USER/ASSISTANT 消息构建历史，包含上轮追问及分诊摘要，再交给引擎；保留原有分诊版本存储。 |
| `triage/infrastructure/demo/RuleBasedTriageEngine.java` | `clarificationPrompt/triage` 转传历史；原 P0 `assessSafety`、`needsClarification`、工具轨迹、grounded 拒答及 `SAFETY_RULE` 路径保留。 |
| `triage/infrastructure/llm/OptionalNarrationModel.java` | `buildMessages` 保留最多 5 个 USER 轮次、历史总长 ≤4000 字，构造 LangChain4j 消息列表；`guide/explain` 改用列表调用。`validate/removeMedicationDirections` 及 `<evidence>`、不可信声明、空依据拒答和 demo 回退保留。原因是单字符串调用每轮失忆。 |
| `tools/application/HospitalToolExecutor.java`、`knowledge/infrastructure/qdrant/HybridKnowledgeCatalog.java`、`resources/application.yml` | `execute/retrieve/search` 使用 `ai.retrieval.min-score` 默认 0.28 与 `ai.retrieval.semantic-min-score` 默认 0.45，避免散落常数。原 P0 工具执行与审计保留。 |
| `knowledge/domain/KnowledgeCatalog.java`、`knowledge/infrastructure/demo/InMemoryKnowledgeCatalog.java`、`knowledge/api/AdminKnowledgeController.java` | `approveDocument` / `POST /api/admin/knowledge/{id}/approve`：上传仍先待审核，管理员批准后才进入本地检索，并触发索引；上传编码与类型限制保留。 |
| `knowledge/infrastructure/qdrant/QdrantSemanticIndex.java` | `ensureIndexed/pointId` 用稳定 point ID 只对新批准片段做 embedding/upsert；失败返回 false，保留本地检索，修复一次索引后永不更新的问题。 |
| `frontend/src/features/admin/AdminPage.vue`、`frontend/src/App.vue` | `searchKnowledge/approveKnowledge` 接通检索和审核按钮；不改变患者流程。 |
| `docs/ARCHITECTURE.md`、`docs/P0_IMPLEMENTATION_2026-09-29.md`、`README.md` | 说明六道生成侧拦截、模型上下文与阈值、审核和索引真实边界。 |
| 原 P0 越界但保留：`triage/domain/TriageSafetyPolicy.java`、`triage/api/AdminHumanReviewController.java`、`triage/infrastructure/mybatis/MybatisTriageStore.java`/`TriageMapper.java`、`tools/api/McpProtocolController.java`、`observation/infrastructure/mybatis/CallLogMapper.java`/`MybatisCallLogStore.java`、`booking/application/SimulationBookingService.java`、`shared/security/JwtService.java`/`DemoDeploymentGuard.java`、`shared/model/Models.java`、`resources/schema.sql`、患者端 `TriagePage.vue`/`AssessmentCard.vue`/`types.ts` | 分别对应 `assess/emergencyAdvice`、`requestHumanReview` 与存取、`tools/list/tools/call`、`calls/record`、`book`、JWT/本机保护、结构化安全结果、表结构与展示。它们用于危急拦截、人工排队、工具可观测和预约保护；此次没有回滚或再扩展。 |
| 原 P0 其余保留：`identity/application/DemoLoginService.java`、`triage/api/TriageController.java`、`triage/domain/TriageRecords.java`/`TriageStore.java`、`tools/api/AdminToolController.java`/`DemoToolHttpController.java`、`observation/infrastructure/demo/InMemoryCallLogStore.java`、`frontend/src/override.css` | `login` 的演示开关、`requestHumanReview` 入口、`HumanReview` 契约和存储端口、`run/toggle` 与旧工具入口、日志实现切换、页面提示样式均属原 P0 范围，保持不变。 |

测试文件：`triage/infrastructure/llm/OptionalNarrationModelContextTest.java` 验证第二轮保留上一轮追问及超窗；`knowledge/RetrievalThresholdConfigurationTest.java` 验证默认阈值；`knowledge/QdrantSemanticIndexTest.java` 验证审核后可命中且重复索引幂等；`triage/RuleBasedTriageEngineTest.java` 更新接口参数；原 P0 `TriageConversationTests.java` 的安全、人工复核和 MCP 回归仍运行。

## 验证

JDK 17：在 `backend` 执行 `mvn test -q`，**35 项通过、0 失败、0 错误**。在 `frontend` 执行 `npm run build`，类型检查与构建通过。本轮 Qdrant 测试使用模拟 HTTP 服务；**没有**调用真实 LLM、Embedding 服务或医院系统。密钥只从环境变量读取，仓库不保存密钥。

## 面试常问

1. **为什么模型不覆写风险结论？** 危急信号不能受生成文本随机性、提示注入或模型超时影响。服务端规则先定结构化风险，紧急时直接 `SAFETY_RULE`；模型只解释非紧急结果。规则本身仍需临床审核。
2. **幂等＋扣号怎么防并发？** `SimulationBookingService.book` 先按患者与幂等键查已有预约，数据库事务内对剩余号源做条件更新；只允许更新一行成功，否则返回冲突。唯一约束防止同一键重复落单。真实 HIS 仍需对端幂等协议。
3. **检索阈值拦不住幻觉怎么办？** 阈值只控制候选片段相关度，不能证明生成句子有依据。当前以 grounded 拒答、输出过滤与低置信度人工复核降低风险；上线前还需要逐句证据校验、来源版本管理、临床评测和运行监控。
