# AI 智慧医院智能导诊系统｜后续扩展说明

> 最后更新：2026-09-24。本文以当前代码为准，明确区分“已运行的演示能力”和“待接入的生产能力”。

## 1. 产品定位与范围

本项目聚焦辅助导诊闭环：

`症状描述 → 安全筛查 → 知识检索 → Agent 工具轨迹 → 科室/号源建议 → 预约 → 后台观测`

它不是电子病历（EMR）或诊疗系统，不提供诊断、处方、治疗意见，也不能替代紧急医疗服务。

当前角色只有：

| 角色 | 已有能力 |
| --- | --- |
| 患者（PATIENT） | 演示登录、预问诊、挂号、查看自己的预约与分诊摘要 |
| 系统管理员（ADMIN） | 知识资料、MCP 工具开关、调用观测 |

当前没有医生角色、医生登录、接诊工作台、排班维护、病历书写或患者档案管理。它们属于后续扩展，而非遗漏的已完成功能。

## 2. 当前实现状态

| 能力 | 当前实现 | 后续生产化方向 |
| --- | --- | --- |
| 登录与权限 | 演示 JWT；患者/管理员两角色 | Spring Security、密码哈希、刷新令牌、权限矩阵、审计 |
| 分诊 | 关键词规则 + 红旗症状规则 + 固定结构化结果 | LangChain4j Agent + 结构化输出校验 + 人工复核策略 |
| 模型 | 默认 `AI_MODE=demo`；无密钥时不调用模型 | 配置 OpenAI/DeepSeek/Qwen 兼容模型与限流、重试、成本控制 |
| RAG | 内存资料、关键词检索、演示切片数 | 文档解析、真实 Embedding、Qdrant、召回/重排、来源版本化 |
| MCP | 本地 HTTP 工具门面 | 独立 MCP Server、标准 MCP Client、工具 schema、超时/熔断 |
| 医生与号源 | 4 位演示医生、内存原子扣减 | MySQL 表、排班日历、事务与乐观锁、号源冻结/释放 |
| 预约 | 创建并查询预约 | 幂等键、取消、改期、支付/缴费对接、消息通知 |
| 观测 | 内存调用日志和工具轨迹 | 持久化 trace、sessionId/预约单关联、指标平台、告警 |

## 3. 当前模块与接口地图

### 患者端

| 页面 | 关键接口 | 说明 |
| --- | --- | --- |
| 首页 | `GET /api/doctors` | 展示演示医生/号源 |
| 智能预问诊 | `POST /api/triage/sessions/{id}/messages` | 返回风险等级、科室、医生、RAG 依据、工具轨迹 |
| 流式预问诊 | `POST /api/triage/sessions/{id}/messages/stream` | SSE：`status`、`tool`、`result` |
| 预约挂号 | `POST /api/appointments` | 创建预约并扣减号源 |
| 我的就诊 | `GET /api/appointments`、`GET /api/triage/sessions` | 仅预约与分诊摘要，不含病历 |

### 管理端

| 页面 | 关键接口 | 说明 |
| --- | --- | --- |
| 管理首页 | `GET /api/admin/dashboard` | 简化统计 |
| 医学知识库 | `GET/POST /api/admin/knowledge`、`POST /api/admin/knowledge/upload` | 资料查看、演示入库、上传 |
| 知识检索 | `GET /api/admin/knowledge/search?q=` | 当前为关键词检索 |
| Agent 工具中心 | `GET /api/admin/tools`、`PATCH /api/admin/tools/{code}/toggle`、`POST /api/admin/tools/{code}/run` | 查看、启停、试运行 |
| AI 调用观测 | `GET /api/admin/calls` | 调用时间、Token、耗时、工具轨迹 |

### MCP 工具边界

`GET /mcp/tools` 返回工具清单，`POST /mcp/tools/{code}` 调用工具。当前注册：

- `symptom_tag_search`
- `department_search`
- `doctor_schedule_search`
- `medical_knowledge_retrieve`

## 4. 关键安全规则

红旗症状包括急性/持续胸痛、严重呼吸困难、意识不清、晕厥、昏迷等。命中后必须：

1. 输出“紧急”风险等级和紧急就医提醒；
2. 不生成普通门诊医生或号源建议；
3. 记录规则命中和完整调用轨迹；
4. 不允许模型覆盖该安全结论。

所有模型输出仅能丰富解释文本。风险等级、推荐科室、医生和预约资格应由后端结构化策略校验。

## 5. 推荐的扩展顺序

### 第一阶段：让演示数据可持久化

1. 以 MySQL 落地用户、科室、医生、排班、号源、预约、会话、消息、知识文档、知识片段、Agent 调用轨迹。
2. 预约创建使用数据库事务与条件更新，例如 `remaining > 0` 时扣减；加入唯一幂等键。
3. 所有列表按当前登录用户和角色隔离。
4. 将 `HospitalDemoService` 拆分为 repository、domain service、application service。

建议实体关系：

`User 1-N TriageSession 1-N TriageMessage`

`Department 1-N Doctor 1-N ScheduleSlot 1-N Appointment`

`KnowledgeDocument 1-N KnowledgeChunk`
`TriageSession 1-N AgentTrace`，`Appointment N-1 TriageSession`

### 第二阶段：真实 RAG

1. 上传 TXT/Markdown/PDF 后抽取文本、清洗、按 token 进行重叠切片。
2. 为每个片段生成 embedding，写入 Qdrant；MySQL 存文档、片段、版本、Qdrant pointId。
3. 查询链路使用“向量召回 → 可选重排 → 最小来源集”，返回标题、文档版本、片段、得分。
4. 对知识资料建立审核、发布、下线、重建和回滚状态。

### 第三阶段：真实模型与 Agent

设置环境变量：

```powershell
$env:AI_MODE='openai-compatible'
$env:AI_BASE_URL='https://你的兼容接口/v1'
$env:AI_API_KEY='你的密钥'
$env:AI_MODEL='你的模型名'
```

接入原则：

- 模型只负责追问与解释；
- 工具必须有 JSON schema、参数校验、白名单、超时和重试；
- 分诊结果使用 JSON Schema/Java record 校验后才能展示或预约；
- 高风险情形优先执行规则引擎，不等待模型；
- 记录模型版本、prompt 版本、token、耗时、检索来源、工具参数摘要。

### 第四阶段：完整医院业务后台

若需要接近示例的全量后台，按以下顺序增加：

1. 基础数据：用户、科室、医生、症状标签；
2. 排班管理：日历、出诊时段、号源、停诊、批量操作；
3. 患者档案：仅在合规授权前提下管理，采用严格行级权限与脱敏；
4. 医生工作台：待接诊队列、接诊记录、只读查看 AI 分诊依据；
5. 运营看板：挂号趋势、号源利用率、科室/医生维度、分诊采纳率；
6. AI 运营：模型配置、Prompt 版本、工具注册与工具调用日志。

## 6. 当前已知技术债

- 内存数据重启即丢失。
- 预约没有幂等键；重复提交可能生成多张预约。
- 单 JVM 锁仅适用于单实例，不能替代数据库并发控制。
- 调用观测未持久化，也未包含 sessionId 与 appointmentId 的强关联。
- RAG 不是真实向量检索；Docker Compose 中的 MySQL/Qdrant 尚未接入代码。
- `/mcp` 是 HTTP 门面，不是独立标准 MCP 服务。
- 当前前端用演示路由快速进入，生产环境应改为标准登录、会话过期处理与权限路由守卫。

## 7. 验收与回归建议

每次扩展至少覆盖：

1. 普通症状：检索依据、工具轨迹、推荐医生、预约成功；
2. 红旗症状：紧急提示、无普通号源；
3. 预约幂等与并发：同一请求重复提交、多个用户抢最后一个号；
4. 权限：患者只能查看本人数据，管理员/医生按最小权限访问；
5. RAG：来源可追溯、已下线资料不再召回；
6. 模型异常：超时、限流、工具失败时的安全降级；
7. 审计：通过 sessionId 能串起请求、检索、工具、模型、预约。

本次已有接口级验收见 [`QA_RUN_2026-09-24.md`](../QA_RUN_2026-09-24.md)。
