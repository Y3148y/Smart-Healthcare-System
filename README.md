# AI 智慧医院智能导诊系统

这是一个以聊天式预问诊为中心的智慧医院原型。患者可以多轮描述症状、查看本人历史会话与症状时间线，获得带知识依据的就诊方向，并创建**模拟预约**。目前没有合作医院，号源和预约都不是真实医院数据。

## 产品定位与轻量改造范围

目标是面向门诊入口的辅助预问诊、危险信号提醒与挂号导航，不替代医生诊断，也不替代医院 HIS/EMR。当前是接入真实模型与向量检索的原型，尚不具备真实医院上线条件。

当前面向成年人本人、声明非孕产期的常规就医咨询与模拟挂号用户；后台面向知识维护、AI 运行检查及导诊申请处理人员。入口声明不是医学核验，风险规则未经临床审核。当前没有合作医院或真实 HIS 联调。

系统只有 PATIENT、ADMIN 两种业务角色。医生目前是演示目录数据，不是可登录角色。用户已确认下一阶段采用最小范围：**患者端 + 导诊管理员端**，沿用单体、MyBatis、数据库和 Qdrant，不拆微服务或新增框架。

| 板块 | 当前实际能力 | 本轮计划，尚未完成 |
| --- | --- | --- |
| 患者端 | 首页、多轮预问诊、历史会话/分诊版本、模拟预约、本人症状时间线 | 修复顺序与推荐质量，增加必要本人基础资料 |
| 导诊管理员端 | 首页、医学知识库、检索调试、工具中心、AI 观测、人工申请处理、目录/最小号源管理 | 按申请关系查看必要摘要 |
| 医生/科室 | 数据库维护科室、医生、当前日期/容量；可新增/编辑/启停，无物理删除 | 完整排班与真实医院目录同步暂不建设 |
| 患者档案 | 本人基础资料页、聊天和自述时间线；资料支持持久化更正与版本冲突保护 | 不生成诊断画像，不是医院病历；不默认向管理员开放 |
| 病历与医生端 | 未实现；聊天记录不是正式病历 | 本阶段不做医生登录、正式病历、处方、检查报告 |
| HIS | 未实现真实请求、身份匹配、挂号确认或对账 | 仅保留适配边界，待合作医院明确接口规范 |

不新增支付、短信、复杂排班或随访。人工申请尚无医院值守保障，不能承诺实时处理；系统管理员不默认获得浏览所有患者医疗记录的权限。轻量化不取消权限、危险信号闸门、幂等扣号或失败反馈。

分阶段任务与验收见 [轻量改造清单](docs/LIGHTWEIGHT_DELIVERY_PLAN.md)。科室匹配与生成措辞仍待检查，不能称为完整可试用闭环。
消息排序已在第一批修复并复验，旧记录顺序不可完全恢复；当前科室/医生/号源管理入口为 `?demo=admin&page=admin&adminPage=catalog`，见 [目录管理记录](docs/CATALOG_MANAGEMENT_2026-10-03.md)。目录扩充不代表新科室的医学匹配已完成。患者导航“我的资料”入口为 `?demo=patient&page=profile`；人工导诊权限摘要与问诊质量仍待推进。

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

`docker compose up -d` 可启动 MySQL 与 Qdrant；默认离线配置不依赖它们。MySQL 此阶段尚未在本机实例上完成联调（配置位于 `backend/src/main/resources/application-mysql.yml`）。可通过 `AI_MODE=openai-compatible`、`AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 启用兼容模型；`AI_MAX_TOKENS` 可调整单次输出预算（默认 4096，非实际消耗量）。密钥只允许通过进程环境变量注入。当前工具仍连接演示医院数据，不代表真实 HIS 接入或完整 MCP Agent 编排。

当前默认检索为通用字符 n-gram BM25，不靠逐症状扩充词表；完整路径为 **BM25 + 百炼 embedding/Qdrant → 单一 RRF 融合 → 百炼 rerank → 引用片段**。配置 `AI_EMBEDDING_MODEL`、`AI_EMBEDDING_API_KEY`、`AI_EMBEDDING_BASE_URL`、`AI_QDRANT_URL` 及 `AI_RERANK_MODEL`、`AI_RERANK_URL` 后可启用外部检索。使用 `rag-live` profile 时强制要求两项服务配置，依赖失败不发布未经重排的候选。`scripts/start-rag-live.ps1` 使用独立验证数据库和 8092 端口，不改写既有会话。真实百炼/Qdrant 评测与未解决问题见 [RAG 改造记录](docs/RAG_REDESIGN_2026-10-02.md)。

模型回答会携带最近最多 5 个用户轮次的会话消息（历史合计不超过 4000 字符）；演示对话模型不调用外部 LLM，检索依赖是否调用外部服务由检索配置单独决定。`AI_RETRIEVAL_MIN_SCORE`（默认 0.28）现在是 BM25 原始分门槛，`AI_SEMANTIC_MIN_SCORE`（默认 0.45）是向量相似度门槛；不同分值不可混用。`AI_RERANK_MIN_SCORE` 在默认配置为 0.5，`rag-live` 为小样本评测后暂定的 0.15，均不是医学可信概率。新上传知识需要管理员批准才会参与检索；批准后尝试增量更新 Qdrant，失败时默认配置允许明确标识的本地降级，`rag-live` 不返回检索依据。资料及审批状态目前只保存在内存，服务重启会丢失。

仅凭未映射的鼻部症状和挂号意图，不再生成全科可预约医生；`待补充信息` 的分诊版本也不能预约。此次处置一致性修正见 [D4 修复记录](docs/D4_DISPOSITION_CONSISTENCY_2026-10-02.md)，旧行为的历史记录见 [鼻部症状挂号问题记录](docs/INCIDENT_NASAL_BOOKING_2026-10-01.md)。

疑似食物相关全身皮疹的确定性安全预警与普通预约阻断，见 [过敏预警问题记录](docs/INCIDENT_ALLERGY_WARNING_2026-10-01.md)；该规则尚未经过临床审核。

> 系统仅用于辅助分诊和挂号演示，不输出诊断、处方或治疗建议。出现胸痛、严重呼吸困难、意识障碍等症状时会优先给出紧急就医提醒。

当前 P0 变更、测试结果与上线阻断项见 [P0 实施及验收记录](docs/P0_IMPLEMENTATION_2026-09-29.md)。演示登录接受任意非空密码，只可用于本机演示；对外部署必须关闭 `AI_DEMO_AUTH_ENABLED` 并接入正式认证。

本轮四项返工、文件与函数清单、验证和答辩要点见 [P0 返工讲解页](docs/P0_REWORK_BRIEF_2026-09-29.md)。

Qdrant 真实向量检索的本机实测数据（命中分数、降级与恢复、无密钥底线）及四种状态/三问讲解材料见 [Qdrant 实测记录](docs/QDRANT_LIVE_VERIFICATION_2026-09-30.md)。

多候选场景下 LLM 输出结构化分诊决策（白名单、置信度、依据护栏与四态回退）的实现、实测与边界见 [结构化分诊决策记录](docs/LLM_STRUCTURED_TRIAGE_2026-10-01.md)。安全评估与风险等级始终由服务端规则控制，模型不能修改。

当前代码分层与依赖方向见 [项目分层架构](docs/ARCHITECTURE.md)；此前的阶段 A 实施、接口收敛与测试记录见 [阶段 A 实施与测试记录](docs/PHASE_A_PROGRESS_2026-09-27.md)。

最新真实运行修正：重排模型为 `qwen3.7-text-rerank`，`rag-live` 阈值已改为 0.5，旧 0.15 仅属历史校准；聊天 `glm-5.3` 使用独立业务空间地址，不复用 embedding 地址，已实测两轮 LIVE。启动脚本需 `-ChatBaseUrl` 或进程 `AI_BASE_URL`。详见 [重排实测](docs/RERANK_MODEL_SWITCH_2026-10-03.md) 和 [聊天地址修正](docs/CHAT_WORKSPACE_FIX_2026-10-03.md)。这些最新结果不代表所有回答或科室推荐正确，也不代表全量回归全绿。

预问诊的多轮收集、预约触发条件和紧急信号处理见 [多轮预问诊与安全预警](docs/TRIAGE_CONVERSATION_AND_SAFETY.md)。

后续扩展的模块边界、接口地图、真实模型/RAG/数据库接入方式与已知限制见 [后续扩展说明](docs/EXTENSION_GUIDE.md)；接口联调记录见 [2026-09-24 验收记录](QA_RUN_2026-09-24.md) 和 [2026-09-26 全链路回归记录](QA_RUN_2026-09-26.md)。

管理员后台新增文档原文/来源/片段与审批、增量索引同步、逐候选检索调试、配置与执行状态观测、工具错误详情和日志分页。入口为 `?demo=admin&page=admin&adminPage=knowledge`，检索调试使用 `adminPage=retrieval`，观测使用 `adminPage=observe`。当前索引状态是本进程写入记录，检索摘要只保留内存中最近 100 条，不是实时健康探针或完整持久化审计。验证结果、当前百炼 Arrearage 和会话排序复验问题见 [后台交付记录](docs/ADMIN_AI_WORKSPACE_2026-10-03.md)。

真实模型连通性验证与安全配置原则见 [模型连通性记录](QA_MODEL_CONNECTIVITY_2026-09-25.md)。密钥不得写入项目文件。

登录与文本编码问题的故障原因、修复和回归项见 [故障记录](docs/INCIDENT_LOGIN_AND_TEXT_ENCODING_2026-09-25.md)。

专业 RAG 资料的官方来源、整理规则和检索验证见 [RAG 医学知识来源目录](docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md)。

固定返回呼吸内科的问题、真实模型验证和修复记录见 [预问诊故障记录](docs/INCIDENT_TRIAGE_FIXED_RESULT_2026-09-26.md)。

患者页旧令牌导致数据加载失败，以及最新模型 API 健康检查见 [患者加载与 LLM 健康记录](docs/INCIDENT_PATIENT_LOAD_AND_LLM_HEALTH_2026-09-26.md)。
