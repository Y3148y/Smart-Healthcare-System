# AI 智能导诊预约系统

以多轮聊天预问诊为核心的导诊原型，包含患者端和导诊管理员端。目标是帮助患者描述症状、识别危险信号、了解就诊方向并衔接预约。

目前没有合作医院，医生、号源和预约为演示数据；真实 HIS 对接与临床审核尚未完成。聊天记录和症状时间线不是正式病历。

当前交付目标是完整可用、可由医院接手的工程版本，不是上线医疗服务。医院后续负责替换和审核医学资料、核验安全策略、接入正式身份及HIS并完成上线审批；这些外部工作不计作本项目已实现能力。工程交付仍须验收多轮咨询、故障处理、分诊与模拟预约闭环及可重复质量评估。

## 功能范围

| 模块 | 已有能力 | 当前边界 |
| --- | --- | --- |
| 患者端 | 多轮预问诊、历史会话与分诊版本、症状时间线、本人资料、模拟预约、人工导诊申请 | 回答质量仍需改进；不提供诊断或处方 |
| 导诊管理员 | 科室/医生/最小号源管理、知识审批、检索调试、工具管理、调用观测、人工申请处理 | 没有医院实时值守保障；不默认开放所有患者资料 |
| 医生与 HIS | 医生目录记录、业务适配边界 | 没有医生工作台，没有真实医院身份匹配、挂号确认或对账 |

## 架构与流程

Spring Boot 单体按业务模块组织 api、application、domain、infrastructure；MyBatis 执行持久化 SQL。默认 H2，另有 MySQL 配置，真实 MySQL 联调未完成。

```text
患者聊天 → 会话服务 → 安全规则与流程判断 → 本地工具执行器
         → 医学检索／科室与号源查询 → LLM 与输出检查 → 保存结果
管理员或外部调用方 → 管理员认证的 /mcp → 同一个工具执行器
```

项目定位是基于 RAG 和 LLM 的智能导诊预约原型，不是自主 Agent 或 Agentic RAG。当前是后端编排的 Workflow，没有模型自主 Function Calling 循环或多 Agent 编排。/mcp 是工具列表与调用的协议子集演示入口。安全规则控制风险和预约限制，模型不能解除限制；规则未经临床审核，未命中不能排除急症。

SSE 推送真实处理阶段，正文检查完成后一次返回，尚非逐 token 输出。

## RAG 当前状态

```text
已批准本地正文 → 段落切分 → BM25 + embedding/Qdrant
查询 → 两路召回 → RRF → 百炼 rerank → 引用片段 → 回答检查
```

应用不再内置旧11篇简短医学摘要；历史内容仅为test/resources工程回归夹具，不进入应用包。新库需管理员导入正文与元数据、核对许可并审批；已有数据库恢复持久化内容。本机已发布的一般科普资料、源正文与哈希保留本地，未经专业临床审核，也不随公开仓库自动初始化。URL仅是来源标识，问答不重新抓网页；切分上限420码点、重叠60码点。完整语料质量验收未完成。

已有可重复索引构建清单、BM25 索引复用、Qdrant 幂等写入与分阶段工程评估。片段记录稳定ID、内容版本、章节、位置和哈希；目录同步写入向量溯源，并检查召回payload与请求快照一致。完整正文采集清洗、适用性过滤、不可变语料版本、审核历史、索引原子发布与回滚仍需补齐。小型工程评估集不是临床审核集，检索命中和模型核对通过不等于医学正确。

管理员导入canonical资料时，数据库保留待审正文哈希、客户端核验的来源快照清单和抽取告警；原网页快照正文不上传，服务端不把该清单当作独立来源认证。

默认允许明确标识的本地检索降级；rag-live 要求向量与重排依赖。demo 对话不调用外部 LLM，检索依赖按配置独立启用。详细事实与限制见 [RAG 设计与验证](docs/RAG.md)。

额度不足时可显式设置 `ai.rerank.enabled=false` 和 `ai.retrieval.require-rerank=false`；两者必须同时配置，否则严格配置拒绝启动。此模式保留向量检索与RRF，标记为未重排，不等于严格RAG验收通过；不要关闭安全或回答依据核对来绕过依赖故障。

## 本地运行

需要 JDK 17、Maven 和 Node.js 20+。项目根目录打开两个终端：

```powershell
# 后端默认 8080；本机 JDK 路径按实际安装替换
$env:JAVA_HOME = 'E:\JDK17\jdk-17.0.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd backend
mvn spring-boot:run

# 另一个终端：前端默认代理 8080
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 5188
```

访问 http://127.0.0.1:5188。演示账号 zhangsan、lisi、admin，任意非空密码。默认数据库在 backend/data/。后端改为 8081 时必须同步前端代理。

后端使用8081时，在前端终端设置 `$env:VITE_API_PROXY_TARGET='http://127.0.0.1:8081'` 后执行上述dev命令；无需改源码。该变量只是本地开发代理地址，不是模型密钥；不要把任何密钥放进VITE_*变量。

若本机npm转发参数异常，可在frontend目录直接执行 `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5188 --strictPort`；strictPort防止端口占用时悄悄换端口。

本地启动MySQL与Qdrant开发依赖时，从项目根目录设置密码并启动：

```powershell
$env:MYSQL_ROOT_PASSWORD = Read-Host 'MySQL local root password'
docker compose up -d mysql qdrant
$env:AI_DB_URL = 'jdbc:mysql://127.0.0.1:3306/ai_hospital?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:AI_DB_USER = 'root'
$env:AI_DB_PASSWORD = $env:MYSQL_ROOT_PASSWORD
$env:AI_QDRANT_URL = 'http://127.0.0.1:6333'
```

Compose端口只绑定本机；其MySQL root凭据仅供本机开发。医院部署需单独建立最小权限DB用户、配置网络访问控制与有认证的向量服务，不应直接复用此开发Compose。

### 非本机接入前置条件

应用当前只有本地演示登录，没有医院身份提供方适配器。关闭 `AI_DEMO_AUTH_ENABLED` 会停用演示登录，但不会自动接上SSO、OIDC或医院账号；在受控联调环境开放患者入口前，医院需提供并验证认证适配及角色映射。

持久部署还需配置：

- `SPRING_PROFILES_ACTIVE=mysql`，否则仍使用默认H2。
- 独立的 `AI_DB_USER` / `AI_DB_PASSWORD`，该DB账号应按实际数据表授予最小权限；Compose中的root账号只用于本机开发。
- 由密钥管理器注入、跨实例保持一致且至少32字节的 `AI_JWT_SECRET`。未配置时应用启动会随机生成，进程重启后此前令牌失效；不得把密钥放进README、镜像或仓库。
- `AI_DEMO_AUTH_ENABLED=false`，并配置真实身份验证/角色映射。不能只关演示登录就当成认证改造完成。
- 受控的模型与embedding配置。正式网络部署不得将无认证的本地Qdrant开发容器暴露到公网；应使用受访问控制的服务或网络隔离。

当前未实现医院身份集成、生产部署编排或HIS适配；以上是医院接手前置项，不表示已有可直接上线的配置。

真实模型通过进程环境变量 AI_MODE=openai-compatible、AI_BASE_URL、AI_MODEL、AI_API_KEY 配置。向量检索配置 AI_EMBEDDING_MODEL、AI_EMBEDDING_API_KEY、AI_EMBEDDING_BASE_URL、AI_QDRANT_URL；重排配置 AI_RERANK_MODEL、AI_RERANK_URL 与对应密钥变量。实际默认值以 backend/src/main/resources/application*.yml 为准。

密钥不得写入文件或 GitHub。演示认证仅限本地，对外部署前必须关闭 AI_DEMO_AUTH_ENABLED 并接入正式认证。

## 验证与目录

```powershell
# 使用 JDK 17
cd backend
mvn clean test
# 另一个终端
cd frontend
npm test
npm run build
```

普通测试不调用真实模型，外部 RAG 集成测试需显式启用。不要使用 JDK 25 跑当前 Mockito 测试。工程测试通过不代表临床验收。

可重复评估入口见 [RAG工程验证](docs/RAG.md)：主题目标命中、片段Recall/Precision/MRR和答案正确性/忠实度是不同指标。质量计算需要版本绑定的人工标签；缺标签时保持未知，作者开发标签不能冒充独立留出评估。报告与医学正文保留本机，不公开提交。

| 目录 | 内容 |
| --- | --- |
| backend/ | 分层业务、数据库、模型与检索适配器、测试 |
| frontend/ | Vue 患者端和管理员端 |
| tools/ | 知识来源同步工具 |
| knowledge/ | 本机业务资料、来源快照、历史稿和发布回执；正文默认不公开提交 |
| scripts/ | 启动与专项验证 |
| docs/ | 架构与 RAG 技术说明；本机另保留当前方案、状态和安全缺口 |

技术说明只保留 [架构与业务边界](docs/ARCHITECTURE.md) 和 [RAG 设计与验证](docs/RAG.md)。运行与功能范围以本页为入口，公开提交规则见 [贡献说明](CONTRIBUTING.md)。

## 医院接手清单

交付代码不等于医疗上线。接手方应按以下顺序验收，不使用开发者本机数据作为正式基础库：

1. 从选定Git提交构建，记录JDK17、数据库和模型配置；密钥通过进程环境注入。备份持久化数据库，不复制演示患者资料到正式环境。
2. 建立正式科室、医生与有效排班。过期排班不会自动续期；新日期使用独立号源，历史预约快照保留。当前预约始终是模拟预约。
3. 导入有许可的医学正文和来源元数据，完成专业临床审核、运营审批与索引同步；检查批准/撤回/失败恢复和版本一致性。运营审批不是临床审核。
4. 替换演示认证，确定患者、管理员及获授权导诊人员的访问边界、审计与留存制度。不能仅关闭演示登录后便声称正式身份系统已完成。
5. 接入HIS时替换模拟预约实现，完成医院身份映射、实际锁号/确认/取消/对账、超时回查和幂等；本项目尚无这些真实接口，数据库模拟扣号不能代替它们。
6. 使用独立标注资料验收回答与检索质量，再测多轮、急症阻断、故障、人工导诊和历史快照。默认离线测试、真实模型测试与临床审核分别记录；生成超时或依据拒绝不得记成功。

交付前仍需确认：真实MySQL联调、独立回答评审、稳定全流程耗时、正式认证/HIS及临床签署。源码可构建或专项测试通过不能自动勾选这些事项。
