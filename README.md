# AI 智慧医院智能导诊系统

以多轮聊天预问诊为核心的导诊原型，包含患者端和导诊管理员端。目标是帮助患者描述症状、识别危险信号、了解就诊方向并衔接预约。

目前没有合作医院，医生、号源和预约为演示数据；真实 HIS 对接与临床审核尚未完成。聊天记录和症状时间线不是正式病历。

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

当前是后端编排的 Workflow，没有模型自主 Function Calling 循环或多 Agent 编排。/mcp 是工具列表与调用的协议子集演示入口。安全规则控制风险和预约限制，模型不能解除限制；规则未经临床审核，未命中不能排除急症。

SSE 推送真实处理阶段，正文检查完成后一次返回，尚非逐 token 输出。

## RAG 当前状态

```text
已批准本地正文 → 段落切分 → BM25 + embedding/Qdrant
查询 → 两路召回 → RRF → 百炼 rerank → 引用片段 → 回答检查
```

内置资料是 11 篇手工整理的简短 Markdown，并非来源网页完整正文。2026-10-06 在本机数据库另发布了 11 份操作者审核确认的中文一般健康资料，保留英文抽取全文、中文草稿和哈希供核查；不是专业临床审核，也不会随公开仓库自动初始化。URL 是来源标识，回答时不会通过网页搜索重新抓取。知识正文、审批状态与新增资料的来源和适用范围声明由 MyBatis 持久化。管理员录入实际正文和许可依据，另行审批后才进入检索。旧资料缺失的元数据标为未知，不补造。长段按章节和段落切分，上限 420 个 Unicode 码点、重叠 60 个；旧种子资料尚未整体替换。

已有可重复索引构建清单、BM25 索引复用、Qdrant 幂等写入与分阶段工程评估。片段记录稳定ID、内容版本、章节、位置和哈希；目录同步写入向量溯源，并检查召回payload与请求快照一致。完整正文采集清洗、适用性过滤、不可变语料版本、审核历史、索引原子发布与回滚仍需补齐。小型工程评估集不是临床审核集，检索命中和模型核对通过不等于医学正确。

默认允许明确标识的本地检索降级；rag-live 要求向量与重排依赖。demo 对话不调用外部 LLM，检索依赖按配置独立启用。详细事实与限制见 [RAG 设计与验证](docs/RAG.md)。

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

真实模型通过进程环境变量 AI_MODE=openai-compatible、AI_BASE_URL、AI_MODEL、AI_API_KEY 配置。向量检索配置 AI_EMBEDDING_MODEL、AI_EMBEDDING_API_KEY、AI_EMBEDDING_BASE_URL、AI_QDRANT_URL；重排配置 AI_RERANK_MODEL、AI_RERANK_URL 与对应密钥变量。实际默认值以 backend/src/main/resources/application*.yml 为准。

密钥不得写入文件或 GitHub。演示认证仅限本地，对外部署前必须关闭 AI_DEMO_AUTH_ENABLED 并接入正式认证。

## 验证与目录

```powershell
# 使用 JDK 17
cd backend
mvn clean test
# 另一个终端
cd frontend
npm run build
```

普通测试不调用真实模型，外部 RAG 集成测试需显式启用。不要使用 JDK 25 跑当前 Mockito 测试。工程测试通过不代表临床验收。

| 目录 | 内容 |
| --- | --- |
| backend/ | 分层业务、数据库、模型与检索适配器、测试 |
| frontend/ | Vue 患者端和管理员端 |
| tools/ | 知识来源同步工具 |
| scripts/ | 启动与专项验证 |
| docs/ | 架构、方案、验收和历史故障记录 |

技术说明只保留 [架构与业务边界](docs/ARCHITECTURE.md) 和 [RAG 设计与验证](docs/RAG.md)。运行与功能范围以本页为入口，公开提交规则见 [贡献说明](CONTRIBUTING.md)。
