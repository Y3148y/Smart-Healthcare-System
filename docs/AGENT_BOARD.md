# 协作状态板

本文件是 `AGENTS.md` 第 1 条「每个任务必须明确唯一 owner」的落地处。开工前先读本文件，改动后更新。

**本文件与代码冲突时以代码为准，并当场修正本文件。** 事实源会腐化，实测不会。

## 1. Owner 划分

| 范围 | Owner |
| --- | --- |
| `triage/**`、`booking/**`、`catalog/**`、`observation/**` | opencode（主会话，本文件维护者） |
| 所有 `*Test.java`、`src/test/**` | opencode（主会话） |
| `frontend/src/features/triage/**` | opencode（主会话） |
| `docs/HANDOVER_TRIAGE_SAFETY_2026-10-02.md`、`docs/KNOWN_ISSUES_PRECLINICAL.md`、`docs/D4_*.md` | opencode（主会话） |
| `tools/knowledge-sync/**`、`docs/KNOWLEDGE_SOURCES.md`、`docs/KNOWLEDGE_PIPELINE.md`、`docs/BUSINESS_KNOWLEDGE_INVENTORY.md`、`.gitignore` 的 knowledge-sync 三行 | 另一个 opencode 实例 |
| Q1–Q6 裁定、临床措辞与出处裁定 | GPT（**只出裁定，不写生产代码**） |

### 常见误解的更正

- `0a8bac1`（D2 面部肿胀分级）、`83dacd7`（D5）、`895cf7a`/`c3e24ad`（D3 收尾与声明修正）、`57e67bc`/`41d6562`/`e36cb25`/`cbd2008`（D10）、`c150929`（合规拒答显示）**都是 opencode 主会话的 commit，不是 GPT 的**。
- 唯一由 GPT 写的生产代码是 `34c6f5f`（D4 处置一致性）。
- GPT 在 `193c6fc` 之前不写生产代码，只给裁定。把它当成 `triage/**` 的单写手会导致真正的 owner 被挡在门外。

## 2. 不属于本协作的路径

- `D:\IDEAprojects\langchain4j`：**不是** git 仓库，内容是与本项目无关的独立项目（java-ai-langchain4j、XiaoZhiAgent、xiaozhi-ui）。任何 agent 被指派去看那里都会得到「什么都没有」的结论。opencode 主会话的工作目录设为该路径，但全部提交都在 `D:\IDEAprojects\ai`，操作需显式指定目录。

## 3. 进行中的任务

| 任务 | Owner | 状态 |
| --- | --- | --- |
| D10 两步评估 + 孕产状态待确认 + 文案 + 文档 | opencode | 已完成（P4，68/68） |
| 合规拒答前端显示 | opencode | 已完成（`c150929`） |
| 知识源抓取与召回复算 | 另一个 opencode | 进行中（`cli.mjs` / `verify.mjs` / `sources.json` 未提交） |
| 「明显出血」应路由到哪条规则 | 待 GPT 裁定 | 阻塞：文案已出现但规则不识别 |
| 句号分隔是否也阻断组合 | 待 GPT 裁定 | 现取 fail-safe（升级） |
| 给 11 条第一步规则加紧邻否定保护 | 待 GPT 裁定 | 现仅第二步有 |
| A2 概念入数据 | opencode | 未开始 |
| `RuleBasedTriageEngine:77`「第二十一条」条号 | opencode | 待降级为只引文件号+文意 |

## 4. 共享资源冲突

`backend/target` 是共享目录。**同时跑 `mvn test` 会互相覆盖 surefire 报告**，且 `target/surefire-reports/` 会残留已删除测试类的旧报告，直接汇总会多算。并行前先确认没有人在跑。

`git add` 只用具体路径，**禁止 `-A` / `add .`** —— 本仓库常有多方未提交改动，一句 `git add -A` 就会把别人的在制品扫进自己的 commit。

## 5. 环境事实

- 基准 JDK `E:\JDK17\jdk-17.0.1`，须先设 `$env:JAVA_HOME`；默认 `java` 为 1.8，会报 class version 61。
- **不要用 JDK 25**（`D:\Elasticsearch\elasticsearch-9.3.3\jdk`）：Mockito 的 Byte Buddy 仅支持到 Java 23，mock 具体类会产生与业务无关的假失败。
- 本仓库基准为 **JDK 17**，写代码时注意 API 可用性。例：`Matcher.region(int,int)` 可用，JDK 20 的 `matcher(CharSequence,int,int)` 重载不可用。
- `mvn compile` 报 SUCCESS **不足以证明改动被编译**：增量编译可能读旧 target。改动后必须跑 `mvn clean test` 才作数。
- PowerShell 5.1 发中文请求体必须 `[System.Text.Encoding]::UTF8.GetBytes($json)`，否则正文变 `????`，所有安全规则会全部漏判。控制台回显中文同样会花屏，验证中文行为时不要依赖控制台输出，改用断言或写 ASCII 结果文件。
- `www.nhc.gov.cn` 对程序化请求返回 HTTP 412（WAF）。标 `manual` 人工读，不要伪装 UA 绕过。
