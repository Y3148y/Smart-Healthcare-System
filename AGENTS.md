# AI 协作规则（适用于本仓库所有 AI 协作者）

> **规则与测试范围的接手方请先读 [docs/HANDOVER_TO_GPT.md](docs/HANDOVER_TO_GPT.md)。**
> 其中列出两项已落地的裁定、一项因 A2 迁移疏漏而阻塞的修复、以及前任 opencode 犯过的
> 四类错误与其根因。

本项目由多个 AI 协作完成（opencode 与 GPT）。为避免互相覆盖和口径分叉，以下规则是硬约束，优先级高于任何单次任务指令。

## 1. 单写手原则

- 同一时间，同一批文件只能有一个写手。
- 每个任务必须明确唯一 owner；owner 写完并验收后，任务才可交接。
- **owner 划分与进行中任务见 [docs/AGENT_BOARD.md](docs/AGENT_BOARD.md) —— 开工前必读，改动后更新。** 注意：GPT 除 D4 外只出裁定、不写生产代码，`triage/**` 的 owner 是 opencode。
- 禁止两个 AI 同时修改同一个任务的代码。若需要并行，必须按模块切分且互不交叉（接口文件、共享文档仍归单一 owner）。

## 2. Git 是唯一交接介质

- 每个验收通过的任务 = 一个 commit，message 写清做了什么（`feat/fix/docs: ...`）。
- 接手任何任务前，先 `git log --oneline -10` + `git status` + `git diff` 恢复上下文，不依赖聊天记忆。
- `git add` 只用具体路径，禁止 `-A` / `add .`：本仓库常有多方未提交改动，`-A` 会把别人的在制品扫进自己的 commit。
- 禁止提交：密钥、`*.env`、`data/`、`target/`、`node_modules/`、`dist/`。
- API 密钥只允许进程环境变量注入，禁止写入任何被 git 跟踪的文件。

## 3. 文档是单一事实源

- 对外口径（README、docs/、讲解材料、面试说法）以仓库文档为准。
- 谁改代码谁同步文档；文档与代码不一致视为未完成。
- 禁止夸大词：生产级、企业级、防幻觉、自主 Agent、多智能体编排（除非代码里真有且有测试）。
- 演示能力必须声明为演示：`/mcp` 是协议子集演示入口、demo 模式不调外部模型、审批状态在内存。

## 4. 复核权分离

- 写的人不复核自己的东西。owner 之外的一方负责验收复核。
- 验收标准以任务书为准，缺一项不验收。
- 测试是底线：改完必须跑 `backend` 的 `mvn test`（**基准 JDK 为 `E:\JDK17\jdk-17.0.1`（JDK 17）**，须先设 `$env:JAVA_HOME`；默认 `java` 为 1.8 会报 class version 61；无任何 `AI_*` 环境变量时必须全绿）和 `frontend` 的 `npm run build`。详见 [docs/KNOWN_ISSUES_PRECLINICAL.md](docs/KNOWN_ISSUES_PRECLINICAL.md) 第四节。
- **不要用 JDK 25 跑测试**：`D:\Elasticsearch\elasticsearch-9.3.3\jdk`（JDK 25）存在，但本项目 Mockito 所依赖的 Byte Buddy 仅支持到 Java 23。在 JDK 25 下，任何 **mock 具体类**（非接口）的用例会报 `Java 25 (69) is not supported by the current version of Byte Buddy`，产生与业务代码无关的假失败。仅 mock 接口的用例在 JDK 25 下碰巧能过，不要据此认为 JDK 25 可用。

## 5. 诚实红线（面试与文档共用）

- 不写没做过的功能，不把框架自带能力说成自研。
- Function Calling、MCP、多智能体、向量 RAG 的真实状态必须按代码现状表述，不得升格。
- 做不了的事（临床审核、真实 HIS 对接、NMPA 资质）明写"未完成/待外部"，不得暗示已完成。
