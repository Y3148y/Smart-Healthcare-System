# LangChain4j 医疗导诊助手

[![构建与测试](https://github.com/Y3148y/Smart-Healthcare-System/actions/workflows/verify.yml/badge.svg)](https://github.com/Y3148y/Smart-Healthcare-System/actions/workflows/verify.yml)
[![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](backend/pom.xml)
[![Docker Compose：MySQL + Qdrant](https://img.shields.io/badge/Docker%20Compose-MySQL%20%2B%20Qdrant-2496ED?logo=docker&logoColor=white)](docker-compose.yml)

基于 **LangChain4j、Spring Boot 与 Vue 3** 的多轮导诊示例应用，覆盖症状咨询、资料检索、就诊方向、模拟预约和管理员知识运营。目标是让开发者复现有业务约束的 LLM 工作流、受控只读工具调用、混合检索与回答溯源。

患者流程由后端工作流编排；LangChain4j 负责模型消息与兼容客户端接入，安全策略、检索与预约权限由应用控制。项目尚未接入真实 HIS，医学规则与资料未经专业临床审核，预约使用模拟号源。

## 当前状态

| 方向 | 已有可复现能力 | 尚未完成的验收 |
| --- | --- | --- |
| Agent + Workflow | 普通咨询可由模型在白名单内选择只读工具；安全分流和预约提交由工作流控制 | 一次合成闭环不代表多场景稳定或医学正确 |
| RAG | BM25、Qdrant、RRF 与可选重排；11篇示例资料可离线校验 | 示例资料仍待使用范围和医学审核，不能直接发布或报告线上召回率 |
| 业务演示 | 患者会话、模拟预约、管理员知识与目录管理 | 没有真实 HIS、医生工作台或正式病历 |
| 工程验证 | JDK17/Node 测试与构建、只读浏览器导航检查 | 关键写操作和故障场景的完整端到端验收仍在进行 |

CI 徽章只表示自动化检查结果，不是临床认证、数据质量证明或项目许可证。

## 界面预览

以下为本地演示账号的实际页面截图，不包含真实患者记录；界面展示不代表已完成临床审核或医院系统对接。

| 患者端：智能预问诊 | 管理员端：业务与知识管理 |
| --- | --- |
| <img src="docs/images/patient-triage.png" alt="患者端智能预问诊页面，显示新会话和输入框" width="640"> | <img src="docs/images/admin-dashboard.png" alt="管理员端首页，显示科室、知识库及运行观测入口" width="640"> |

## 功能

| 模块 | 能力 |
| --- | --- |
| 多轮咨询 | 有界对话上下文、按需追问、一般知识回答、历史会话与分诊版本 |
| 混合检索 | Markdown 章节切分、BM25 + embedding/Qdrant、RRF 融合、可选百炼 rerank |
| 回答溯源 | 片段身份与内容哈希校验、实际引用展示、独立模型支持核对、失败状态区分 |
| 分诊与预约 | 危险信号优先处理、科室/医生/排班查询、事务扣号与幂等回执 |
| 患者端 | 本人资料、会话记录、模拟就诊记录、人工导诊申请 |
| 管理员端 | 科室与医生管理、知识导入/审批/撤回、检索调试、调用观测、人工申请处理 |
| 工程评估 | 冻结语料快照、分阶段 Recall/Precision/MRR、人工答案忠实度与正确性评分工具 |

SSE 只推送处理阶段，正文检查后整段返回，并非逐 token 输出。日常默认启用有界只读 Agent（`AI_AGENT_MODE=ENABLED`）；模型可选择医学检索、科室和模拟排班查询，但不能创建预约。急症和预约仍由工作流控制，模型或工具失败时回退工作流。`/mcp` 是管理员认证的协议子集演示入口，不是患者 Agent 的传输通道。一次百炼合成咨询曾完成模型选工具、执行医学检索和回答核对，但最终未取得可引用的已审批资料；这不构成医学正确性或检索质量验收。具体预算、失败条件与未完成项见 [Agent 与工作流](docs/AGENT.md)。

## 技术架构

| 层 | 技术 |
| --- | --- |
| 模型接入 | LangChain4j 0.35.0、OpenAI-compatible API；特定请求参数使用有界 HTTP 适配器 |
| 后端 | Java 17、Spring Boot 3.3.5、MyBatis |
| 存储与检索 | MySQL、Qdrant、进程内 BM25 索引 |
| 前端 | Vue 3、TypeScript、Vite |
| 验证 | JUnit 5、Node.js Test Runner、独立 RAG 评估 CLI |

```text
患者消息 → 身份/输入校验 → 安全与流程判断
                              ├─ 危险信号 → 就医提示与预约阻断
                              └─ 普通咨询 → BM25 + Qdrant → RRF → 可选 rerank
                                          → 证据判定 → 模型生成与核对
                                          → 保存回答/来源/trace → 返回患者

明确预约 → 科室/医生/号源校验 → 数据库事务与幂等回执
管理员   → 知识导入 → 待审 → 审批 → 检索缓存与向量同步
```

后端按业务模块分层组织，保留领域端口与外部适配器。会话服务负责身份和保存，工作流负责协调，路由返回类型化结果，普通咨询回答链复用既有证据检查。实现细节见 [架构说明](docs/ARCHITECTURE.md)、[Agent与工作流](docs/AGENT.md) 和 [RAG 设计与评估](docs/RAG.md)。

可复现的11篇医学示例资料位于 [knowledge/corpus](knowledge/corpus)，包含中文正文、来源元数据及必要文本快照；[资料说明](knowledge/README.md)和[来源许可](knowledge/LICENSE.md)说明导入方式及使用边界。公开资料不等于临床审核通过，也不会随启动自动导入或批准。

## 快速开始

需要 JDK 17、Maven、Node.js 20+ 与 MySQL。Qdrant 用于向量检索，可通过项目 Compose 启动。

### 1. 准备数据库

以下 PowerShell 命令将密码读取到当前进程环境：

```powershell
$secret = Read-Host 'MySQL password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:AI_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
$env:AI_DB_USER = 'root'
```

使用已有 MySQL 时，先创建 `ai_hospital` 数据库：

```sql
CREATE DATABASE IF NOT EXISTS ai_hospital CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

也可从项目根目录启动本机开发依赖；Compose 会创建数据库并保留数据卷：

```powershell
$env:MYSQL_ROOT_PASSWORD = $env:AI_DB_PASSWORD
docker compose up -d mysql qdrant
```

已有 MySQL 时无需再启动 Compose MySQL。应用启动时初始化数据表；旧 H2 数据不会自动迁移。应用运行依赖仅含 MySQL 驱动，H2 保留为自动化测试依赖。

### 2. 启动后端

在设置了数据库环境变量的终端运行：

```powershell
cd backend
mvn spring-boot:run
```

默认后端为 `http://127.0.0.1:8081`。本机有多个 JDK 时先设置 `JAVA_HOME` 为 JDK 17；IDEA 运行配置选择 JDK 17，并保存数据库和模型环境变量一次，之后直接点击运行，不需要重复添加模式、端口或 profile 参数。

### 3. 启动前端

另开终端：

```powershell
cd frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5188`，前端默认代理后端8081，端口占用时明确报错而不自动换端口。本地演示账号为 `zhangsan`、`lisi` 和 `admin`，密码为任意非空字符串。演示登录与模型调用模式独立：URL 中的 `demo=patient` 表示演示身份入口，实际模型调用由后端配置与本轮处理路径决定。

日常启动无需端口参数。仅自定义部署时才覆盖 `SERVER_PORT` 和 `VITE_API_PROXY_TARGET`；旧 IDEA VM options、环境变量和 profile 的显式设置仍会覆盖默认值，请在首次整理时移除不再需要的旧覆盖项。

### 4. 接入真实模型与检索

日常默认真实模型与只读Agent、关闭rerank，无需选模式或启用 `rag-live`。在 IDEA 的后端运行环境中一次性保存 `AI_DB_USER`、`AI_DB_PASSWORD`、`AI_BASE_URL`、`AI_API_KEY`；模型默认 `qwen3.7-flash-2026-07-15`、embedding默认 `qwen3.7-text-embedding`。模型名取自本项目既有配置，不保证其他账户可用；需要更换时只覆盖对应环境变量。embedding地址/密钥可继承聊天配置，也可单独设置。API密钥缺失/不合法或模型地址为空时启动报错，不悄悄切到demo。启动校验不证明远端服务或额度可用。

IDEA保存的环境变量只供该启动进程及其子进程使用，不提交含凭据的运行配置文件；从新终端运行仍需该终端拥有相同环境变量。纯离线演示显式设置 `AI_MODE=demo`，自动化测试已有独立离线配置。

| 环境变量 | 用途 / 默认值 |
| --- | --- |
| `AI_DB_URL` | 默认连接本机 `ai_hospital` MySQL 数据库 |
| `AI_DB_USER`、`AI_DB_PASSWORD` | 数据库凭据，必填 |
| `AI_MODE`、`AI_AGENT_MODE` | 默认 `openai-compatible` / `ENABLED`，日常无需设置 |
| `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` | 模型兼容接口、模型 ID 与密钥 |
| `AI_EMBEDDING_BASE_URL`、`AI_EMBEDDING_MODEL`、`AI_EMBEDDING_API_KEY` | embedding 接口；地址和密钥可继承聊天配置 |
| `AI_QDRANT_URL`、`AI_QDRANT_COLLECTION` | 默认 `http://127.0.0.1:6333`、`ai_hospital_knowledge_v2`；使用其他既有集合时显式覆盖，不自动迁移数据 |
| `AI_RERANK_ENABLED` | 默认 `false`；有服务和额度后显式启用 |
| `AI_RERANK_URL`、`AI_RERANK_MODEL`、`AI_RERANK_API_KEY` | 可选百炼重排接口与凭据 |
| `SPRING_PROFILES_ACTIVE=rag-live` | 可选严格向量配置；默认不要求rerank，不是日常启动必选项 |
| `AI_RETRIEVAL_REQUIRE_SEMANTIC`、`AI_RETRIEVAL_REQUIRE_RERANK` | 普通配置均默认 `false`；`rag-live` 仅前者默认 `true` |
| `SERVER_PORT`、`VITE_API_PROXY_TARGET` | 默认8081及 `http://127.0.0.1:8081`，日常无需设置 |

临时跳过重排时同时设置 `AI_RERANK_ENABLED=false`、`AI_RETRIEVAL_REQUIRE_RERANK=false`。检索会记录未重排状态，已有重排模式评测结果不能直接沿用。

完整参数以 [应用配置](backend/src/main/resources/application.yml) 为准。密钥通过后端进程环境注入，不写入仓库或 `VITE_*` 变量。

## 知识库与评估

公开仓库提供采集、校验、导入和评估工具，以及11篇有单独来源许可说明的示例正文和必要来源文本；不附带患者数据或未经核实许可的其他资料。空知识库可以启动；管理员可把示例导入为待审资料，但这11篇当前 `permissionStatus=pending`，不能直接审批进入患者检索。需逐篇完成使用范围核查、更新待审元数据并由授权人员审批后才可发布；公开再分发许可不自动等于患者端使用许可或临床审核。URL 是来源标识，在线回答不会通过 URL 临时抓取网页。

知识资产统一放在 `knowledge/`，采集状态和评估报告放在被忽略的 `reports/`。参见 [知识目录](knowledge/README.md) 与 [采集导入工具](tools/knowledge-sync/README.md)。

```powershell
cd tools/knowledge-sync
npm ci
npm test
cd ../rag-eval
npm test
# 仅在逐篇解决权限状态并审批为 READY 后，设置 RAG_EVAL_PASSWORD
npm run evaluate:public
```

评估区分候选召回、最终片段选择和答案质量。当前公开11篇保持待审、权限未决，干净环境不能直接对其运行在线检索评估；不得为跑分擅自将权限改成 `permitted`。公开主题集仅检查12个作者标注的目标文档标题，不是独立留出集或穷举相关片段标签；旧评估集对应历史批准资料，标题与当前语料不符时评估器会拒绝而非记为召回失败。正确率、忠实度及置信校准需要人工标签；未标注时输出未知。

公开语料可独立校验：`node tools/knowledge-sync/validate-document.mjs --corpus` 一次验证全部11篇正文与来源指纹，单篇也可传入具体MD路径；JDK17编译后，`java --class-path backend/target/classes tools/knowledge-sync/ChunkInspection.java knowledge/corpus` 用实际切分器检查原文覆盖、段落不跨片段、章节路径、边界与哈希。当前78个片段中26个短于80码点，实际没有触发长段重叠；这些机械检查不能证明医学条件完整、答案质量或召回率。

## 开发与测试

```powershell
cd backend
mvn test
cd ../frontend
npm test
npm run build
cd ..
node scripts/check-public-files.cjs
node scripts/check-public-content.cjs
```

后端常规测试使用内存测试库和模型桩，无需模型密钥。外部集成测试必须显式启用，具体入口见 [RAG 文档](docs/RAG.md)。
同样的离线检查已配置在 [GitHub Actions](.github/workflows/verify.yml)；远端运行结果应以实际工作流记录为准，不能用本机通过代替。

前后端已在本机启动时，可选用 Node.js 22 和 Chromium 运行只读页面检查：

```powershell
node scripts/test-ui-readonly.cjs
```

默认使用本机 Chrome；其他 Chromium 路径通过 `UI_TEST_BROWSER` 指定。脚本只访问本机地址，用演示账号打开患者预问诊、就诊记录、管理员知识库和运行观测；患者历史会话、知识库 503 与待审资料均为浏览器内合成数据，用于验证历史读取/重置、错误恢复、许可状态对应的审批按钮及审批后详情刷新。合成审批 POST 在浏览器内截获，不修改后端，不提交真实资料、审批或预约。这仍不能替代完整端到端验收。

```text
backend/             分层业务、模型/检索适配器、数据库与后端测试
frontend/            患者端、管理员端及组件测试
tools/knowledge-sync 来源登记、采集清洗、资料校验与待审导入
tools/rag-eval/      检索评估、人工审核包与质量评分
knowledge/           11篇公开示例资料、许可说明及本机忽略的发布回执
scripts/             端到端与运行验证脚本
docs/                架构说明、RAG 设计与评估方法
docker-compose.yml   本机 MySQL/Qdrant 开发依赖
```

## 使用边界与贡献

当前只有本地演示认证，没有医生工作台、正式电子病历、医院 SSO 或 HIS 适配。人工导诊申请不保证实时响应。项目不以医院上线为当前目标；涉及患者真实使用前仍需医学资料与安全规则的专业审核、身份与隐私治理以及独立质量验收。

贡献代码前请阅读 [贡献说明](CONTRIBUTING.md)。保留可复现的源码、测试、已核实许可的示例语料与必要文档；患者数据、其他未经核实许可的医学全文、密钥、生成报告和个人调试文件均不进入公开仓库。仓库当前未声明**代码**开源许可证；[示例资料的许可说明](knowledge/LICENSE.md)不等于代码许可证，使用和分发权限仍需项目维护者确定。
