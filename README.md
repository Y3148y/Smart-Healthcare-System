# AI 智慧医院智能导诊系统

这是一个以聊天式预问诊为中心的智慧医院原型。患者可以多轮描述症状、查看本人历史会话与症状时间线，获得带知识依据的就诊方向，并创建**模拟预约**。目前没有合作医院，号源和预约都不是真实医院数据。

## 运行方式

需要 JDK 17、Maven 3.6.1+ 与 Node.js 20+。本机 JDK 可使用 `E:\JDK17\jdk-17.0.1`。

```powershell
# 终端 1：后端（默认演示 Agent；无需配置模型密钥）
$env:JAVA_HOME='E:\JDK17\jdk-17.0.1'
cd backend
mvn spring-boot:run

# 终端 2：前端
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5188
```

打开 `http://127.0.0.1:5188`。演示账号为 `zhangsan`、`lisi` 或 `admin`，密码任意。默认使用本地持久化 H2 数据库，文件在 `backend/data/`；测试使用独立的内存数据库。

后端持久化通过 MyBatis 3.0.5 Mapper 执行 SQL（分诊会话、评估版本、模拟号源、预约和后台统计）；业务 Service 不再直接使用 `JdbcTemplate`。MyBatis 底层仍使用 JDBC 数据源，切换 Mapper 不等于切换数据库，当前默认数据库仍是 H2。

## 核心接口

- `POST /api/triage/sessions`、`POST /api/triage/sessions/{id}/turns`：创建本人会话并多轮预问诊。
- `GET /api/triage/sessions`、`GET /api/triage/sessions/{id}`、`GET /api/triage/timeline`：查看本人会话、评估版本和症状时间线。
- `POST /api/appointments`：使用 `doctorId`、`sessionId` 和 `idempotencyKey` 创建模拟预约，事务内扣减演示号源；`GET /api/appointments` 仅返回本人记录。
- `POST /mcp`：带管理员认证的 MCP Streamable HTTP 工具列表与调用子集；`/mcp/tools/*` 保留为旧版兼容入口。Agent 在同一进程内调用共用的工具执行器，并非外部 MCP 客户端。
- `POST /api/triage/sessions/{id}/human-review`、`GET/PATCH /api/admin/human-reviews`：提交、查看与处理人工导诊申请队列。
- `/api/admin/*`：知识库、工具中心和 AI 调用观测。

`docker compose up -d` 可启动 MySQL 与 Qdrant；当前默认不依赖它们。Qdrant 向量检索链路已于 2026-09-30 本机实测联调（含停服降级与恢复），MySQL 此阶段尚未在本机实例上完成联调（配置位于 `backend/src/main/resources/application-mysql.yml`）。可通过 `AI_MODE=openai-compatible`、`AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 启用兼容模型；`AI_MAX_TOKENS` 可调整单次输出预算（默认 4096，非实际消耗量）。密钥只应放在进程环境变量中。配置 `AI_EMBEDDING_MODEL`、`AI_EMBEDDING_API_KEY`、`AI_EMBEDDING_BASE_URL` 和 `AI_QDRANT_URL` 后可启用 Qdrant 向量检索；未配置时使用本地医学词和字符向量检索。当前工具仍连接演示医院数据，不代表真实 HIS 接入或完整 MCP Agent 编排。

模型回答会携带最近最多 5 个用户轮次的会话消息（历史合计不超过 4000 字符）；演示模式不调用外部模型。检索阈值可用 `AI_RETRIEVAL_MIN_SCORE`（默认 0.28）与 `AI_SEMANTIC_MIN_SCORE`（默认 0.45）调整。新上传知识需要管理员批准才会参与检索；批准后尝试增量更新 Qdrant，外部索引失败则回退本地检索。资料及审批状态目前只保存在内存，服务重启会丢失。

鼻部症状明确要求挂号时的受控全科回退和验收边界，见 [鼻部症状挂号问题记录](docs/INCIDENT_NASAL_BOOKING_2026-10-01.md)。

> 系统仅用于辅助分诊和挂号演示，不输出诊断、处方或治疗建议。出现胸痛、严重呼吸困难、意识障碍等症状时会优先给出紧急就医提醒。

当前 P0 变更、测试结果与上线阻断项见 [P0 实施及验收记录](docs/P0_IMPLEMENTATION_2026-09-29.md)。演示登录接受任意非空密码，只可用于本机演示；对外部署必须关闭 `AI_DEMO_AUTH_ENABLED` 并接入正式认证。

本轮四项返工、文件与函数清单、验证和答辩要点见 [P0 返工讲解页](docs/P0_REWORK_BRIEF_2026-09-29.md)。

Qdrant 真实向量检索的本机实测数据（命中分数、降级与恢复、无密钥底线）及四种状态/三问讲解材料见 [Qdrant 实测记录](docs/QDRANT_LIVE_VERIFICATION_2026-09-30.md)。

多候选场景下 LLM 输出结构化分诊决策（白名单、置信度、依据护栏与四态回退）的实现、实测与边界见 [结构化分诊决策记录](docs/LLM_STRUCTURED_TRIAGE_2026-10-01.md)。安全评估与风险等级始终由服务端规则控制，模型不能修改。

当前代码分层与依赖方向见 [项目分层架构](docs/ARCHITECTURE.md)；此前的阶段 A 实施、接口收敛与测试记录见 [阶段 A 实施与测试记录](docs/PHASE_A_PROGRESS_2026-09-27.md)。

预问诊的多轮收集、预约触发条件和紧急信号处理见 [多轮预问诊与安全预警](docs/TRIAGE_CONVERSATION_AND_SAFETY.md)。

后续扩展的模块边界、接口地图、真实模型/RAG/数据库接入方式与已知限制见 [后续扩展说明](docs/EXTENSION_GUIDE.md)；接口联调记录见 [2026-09-24 验收记录](QA_RUN_2026-09-24.md) 和 [2026-09-26 全链路回归记录](QA_RUN_2026-09-26.md)。

真实模型连通性验证与安全配置原则见 [模型连通性记录](QA_MODEL_CONNECTIVITY_2026-09-25.md)。密钥不得写入项目文件。

登录与文本编码问题的故障原因、修复和回归项见 [故障记录](docs/INCIDENT_LOGIN_AND_TEXT_ENCODING_2026-09-25.md)。

专业 RAG 资料的官方来源、整理规则和检索验证见 [RAG 医学知识来源目录](docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md)。

固定返回呼吸内科的问题、真实模型验证和修复记录见 [预问诊故障记录](docs/INCIDENT_TRIAGE_FIXED_RESULT_2026-09-26.md)。

患者页旧令牌导致数据加载失败，以及最新模型 API 健康检查见 [患者加载与 LLM 健康记录](docs/INCIDENT_PATIENT_LOAD_AND_LLM_HEALTH_2026-09-26.md)。
