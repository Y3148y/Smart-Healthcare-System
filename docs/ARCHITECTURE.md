# 项目分层架构（2026-09-28）

本项目采用**按业务模块组织的分层架构**，不是完整 DDD，也不是把全部代码放在 `controller/service/mapper` 三个全局目录中的传统 MVC。每个模块按需要划分 `api`、`application`、`domain`、`infrastructure`：

```text
frontend/src/
  App.vue                         # 页面外壳、登录态及患者/管理员入口
  api.ts                          # HTTP 客户端
  features/home/HomePage.vue
  features/booking/BookingPage.vue
  features/visits/VisitsPage.vue
  features/admin/AdminPage.vue
  features/triage/
    TriagePage.vue                # 会话状态与交互
    components/AssessmentCard.vue # 每版分诊结果与版本切换
    types.ts                      # 前端分诊契约

backend/src/main/java/com/aihospital/
  identity/                        # 演示登录
  triage/                          # 多轮会话、分诊版本、规则与模型解释
  booking/                         # 预约资格校验、幂等与原子扣号
  catalog/                         # 医生与号源目录
  knowledge/                       # 本地检索与可选 Qdrant 向量索引
  tools/                           # 工具注册（当前演示实现）
  observation/                     # 统计与调用日志
  shared/                          # API 契约、JWT、统一异常和 MyBatis 通用转换
```

后端每个模块遵循下面的依赖方向：

```text
HTTP 请求 → api → application → domain 接口
                                  ↑
                         infrastructure 实现
```

- `api`：请求校验、身份/角色边界、响应映射；不直接编写 SQL 或分诊业务决策。
- `application`：组织用例，例如发送消息、保存新分诊版本、预约资格核验、创建预约。
- `domain`：业务规则、实体记录和存储/外部能力接口，不依赖 MyBatis、HTTP 或模型 SDK。
- `infrastructure`：MyBatis 持久化、内存演示知识与工具、可选 LLM 适配。

跨模块依赖保持单向：预约可读取分诊结果和目录；分诊可读取目录、知识和工具；目录不依赖预约或分诊。分诊结果和助手回复在同一事务内保存。预约号源扣减由数据库条件更新保障，不在前端做库存判断。`shared/model/Models` 暂时保留既有 JSON 契约，以避免重构时破坏已运行的前后端接口；后续如引入正式医院数据模型，可逐个模块迁移 DTO 映射。

## 生成侧安全边界（P0，不能代替临床审核）

1. **规则管结构**：`TriageSafetyPolicy.assess` 给出风险和命中规则；`RuleBasedTriageEngine.triage` 决定结构化科室、医生和风险，模型只生成文字。`TriageConversationService.send` 在追问或分诊前检查危险信号。
2. **grounded / EVIDENCE_BLOCKED**：`RuleBasedTriageEngine.clarificationPrompt/triage` 在检索无依据时不调用模型，改用拒答与人工导诊提示；`OptionalNarrationModel.guide/explain` 对空证据再次拒绝。
3. **检索阈值**：`HospitalToolExecutor.execute` 传入 `ai.retrieval.min-score` 作为 BM25 门槛；`HybridKnowledgeCatalog.inspect` 使用 `ai.retrieval.semantic-min-score` 筛选 Qdrant 候选，再以单一 RRF 融合排名；`BailianReranker.rank` 使用 `ai.rerank.min-score` 筛选最终片段。配置位于 `application.yml` 与 `application-rag-live.yml`。各分数不可互换，也不能验证模型每句话的真实性。`rag-live` 下依赖失败会阻断证据返回，不伪装成成功的完整检索。
4. **UNSAFE_OUTPUT**：`OptionalNarrationModel.validate` 以 `UNSAFE_OUTPUT` 正则拦截部分确定性诊断与用药表达，不是完整医学安全分类器。
5. **药品句过滤**：`OptionalNarrationModel.removeMedicationDirections` 移除涉及处方、用药或剂量的句子；过滤后仍可能有遗漏。
6. **SAFETY_RULE 不可覆盖**：`RuleBasedTriageEngine.triage` 对紧急情况直接生成 `SAFETY_RULE` 回答，不调用 LLM；`SimulationBookingService.book` 再次阻止普通预约。

多轮消息由 `TriageConversationService.send` 传给 `NarrationModel`，`OptionalNarrationModel.buildMessages` 构造 LangChain4j 消息列表，保留最近最多 5 个用户轮次且历史文本合计不超过 4000 字符，超窗从最早消息开始移除。患者文本和 `<evidence>` 都标为不可信内容。demo 模式仍返回规则与知识兜底，不伪称调用外部模型。

## 当前边界与下一步

当前的 MyBatis 负责会话、版本、预约、号源、统计与调用记录的关系型数据访问。知识资料和工具注册仍是内存演示实现；`/mcp` 是演示 HTTP 工具入口，**不是**已经对接医院的独立 MCP 服务。模型解释是可选适配，缺少配置或调用失败时分诊规则仍可给出演示结果。前端页面已按患者功能和管理端分别拆到 `features`，`App.vue` 保留路由入口、登录态和跨页数据加载；后台多个视图仍共用一个 `AdminPage.vue`。这不是完整 DDD，也不意味着已完成真实医院集成。

## 验证命令

后端：使用 JDK 17 执行 `mvn test`。前端：在 `frontend` 执行 `npm run build`。回归重点是登录、会话历史、多轮消息、分诊版本不可变、多科室建议、预约幂等、管理员权限和已存在的 `/api` 路径。

2026-09-28 本机回归：后端 18 项测试通过，前端类型检查与生产构建通过。重启后经 5188 前端代理实测：患者登录成功；两轮会话产生两版分诊，第一版摘要未被第二版覆盖；“手腕骨折、嗓子疼、痛经”返回骨科、呼吸内科、妇科三个候选方向；相同幂等键重复预约返回同一预约；管理员仪表盘与调用日志可读取，患者访问管理员接口返回 403。上述测试使用演示模式，未验证外部 LLM、真实医院号源或 Qdrant。
