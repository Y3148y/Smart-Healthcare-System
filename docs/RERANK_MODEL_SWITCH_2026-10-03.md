# 百炼重排模型切换

用户指定有免费额度的模型为 `qwen3.7-text-rerank`。启动脚本及显式启用的真实 RAG 集成测试默认模型已从 `gte-rerank-v2` 改为该模型；仍可由启动参数或环境变量覆盖。

原生 DashScope text-rerank 接口实测 HTTP 200。合成查询「胸痛」的两个候选「胸痛应尽快评估」「骨折需要骨科评估」得分分别为 0.7685647072028587、0.2656923165390355。这仅证明接口可用，不证明完整召回质量，也不证明剩余额度。

此前 `HTTP_400:Arrearage` 是旧模型请求的返回，不应外推为同账户全部模型均不可用。

## 完整检索实测

使用 JDK 17、真实百炼 embedding + Qdrant + 新重排模型，冻结开发集和独立留出集分别执行 `mvn test -q -Dtest=RagLiveIntegrationTest -DforkCount=0`（首次先 clean）。进程环境设置 `AI_RAG_LIVE_TEST=true`、`AI_RERANK_MIN_SCORE=0.5`；留出集另设置 `AI_RAG_DATASET=rag-holdout-cases.json`。密钥仅在进程环境注入。

| 集合 | 有依据问题首位命中 | 知识库外问题错误返回依据 | 已标禁止资料命中 |
| --- | --- | --- | --- |
| 开发集 12 条 | 11/11 | 0/1 | 0 |
| 留出集 12 条 | 8/8 | 0/4 | 0 |

逐候选得分与去留见 `docs/evaluations/rag-qwen-development-2026-10-03.json`、`rag-qwen-holdout-2026-10-03.json`。胸痛没有返回四肢骨折资料。此处首位命中不是所有片段均相关的证明：胸痛仍保留呼吸/消化方向资料，鼻部资料的不同分段也会同时出现。完整精度标注、去重体验和更大数据集评测未完成；不宣称临床准确率。

`rag-live` 及集成测试默认阈值更新为已验证的 0.5。旧 gte 模型的 0.15 校准记录保留为历史，不适用于新模型。模型/阈值可覆盖，但不同模型仍需单独评估。

## 当前服务与页面

已重启后端 8081，保留 `.codex-live/data/ai-hospital` 数据库，5188 页面代理不变。运行副本 `.codex-rerank-tests` 含当前源码及此前未提交安全规则/提示改动，不能声称仅包含本次配置改动。管理员运行接口确认 `rerankModel=qwen3.7-text-rerank`、`semanticStatus=READY`；真实检索后模式为 `HYBRID_QDRANT_RERANKED`。

浏览器 `scripts/test-admin-ui.cjs` 默认健康路径通过：患者令牌切管理员、文档详情、资料提交、上传、真实检索、运行记录、受控接口失败、患者权限拒绝、无页面异常。上传的非医学测试资料保持待审批，不进入检索。

## 问诊链路未全部通过

- 合成「流鼻涕两天，没有发烧，想了解可能的原因」命中 2 个知识片段，尝试真实 `glm-5.3` 调用，供应商返回 `Arrearage`，最终 `modelStatus=FALLBACK`。总耗时 1697ms；这不是 LLM 成功作答，也不是 demo 模式。未擅自更换聊天模型。
- 合成「胸痛，喘不过气」返回紧急提示、无医生推荐、`SAFETY_RULE`、不检索 RAG、不调用聊天模型；当前运行规则版本 P9（来自先前未提交改动）。
- 无 AI 环境变量的隔离 `mvn clean test -q -DforkCount=0`：110 项，108 通过、1 失败、1 外部用例跳过。失败仍为 `TriageConversationTests.prescriptionRequestIsRefusedVerbatimAndOffersNoDepartmentOrBooking`，相同时间戳下 UUID 排序让 USER 落在 ASSISTANT 之后；没有削弱测试或修改其他 owner 的持久化代码。
- 隔离前端 `.codex-rag-ui` 的 `npm run build` 通过。

结论：新重排模型及真实 RAG 路径可用；聊天模型额度/权限故障和已有会话排序缺陷未解决。独立复核未完成。密钥未写入文件。
