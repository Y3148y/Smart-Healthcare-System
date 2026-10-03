# 协作状态板

本文件是 `AGENTS.md` 第 1 条「每个任务必须明确唯一 owner」的落地处。开工前先读本文件，改动后更新。

**本文件与代码冲突时以代码为准，并当场修正本文件。** 事实源会腐化，实测不会。

## 1. Owner 划分

| 范围 | Owner |
| --- | --- |
| `triage/**` 的安全规则、`backend/src/main/resources/safety-rules.json` | GPT（`caf8d69` 交接；其他 triage 任务仍需单独交接） |
| `catalog/**`、轻量患者资料新模块、会话排序持久化及对应前端/测试 | GPT（2026-10-03 用户明确统一交接；opencode 暂停这些范围） |
| `booking/**`、`observation/**` | opencode（本轮共享接口调整需先协调；不覆盖在制品） |
| 安全规则相关 `*Test.java` | GPT（`caf8d69` 交接）；其他测试仍归 opencode |
| `frontend/src/features/triage/**` | GPT（仅本轮患者端提示压缩，用户直接指派；其他前端任务仍归 opencode） |
| `frontend/src/style.css` 的分诊样式 | GPT（仅本轮页面遮挡修复；用户直接指派） |
| `knowledge/**`、RAG 检索测试/配置及分诊中的检索 query 构建 | GPT（2026-10-02 用户确认完全接手；以 opencode 的 6b28485 为基线，RAG 共享文件统一单写；不修改 tools/knowledge-sync 与其文档） |
| `frontend/src/features/admin/**`、后台知识详情/检索调试/运行状态接口及专项测试 | GPT（2026-10-03 用户确认新增三块后台功能；不修改 observation 存储、知识源抓取文件或患者安全规则） |
| `docs/HANDOVER_TRIAGE_SAFETY_2026-10-02.md`、`docs/KNOWN_ISSUES_PRECLINICAL.md`、`docs/D4_*.md` | opencode（主会话） |
| `tools/knowledge-sync/**`、`docs/KNOWLEDGE_SOURCES.md`、`docs/KNOWLEDGE_PIPELINE.md`、`docs/BUSINESS_KNOWLEDGE_INVENTORY.md`、`.gitignore` 的 knowledge-sync 三行 | 另一个 opencode 实例 |
| Q1–Q6 裁定、临床措辞与出处裁定 | GPT（规则代码写入权按上述交接范围） |

### 常见误解的更正

- `0a8bac1`（D2 面部肿胀分级）、`83dacd7`（D5）、`895cf7a`/`c3e24ad`（D3 收尾与声明修正）、`57e67bc`/`41d6562`/`e36cb25`/`cbd2008`（D10）、`c150929`（合规拒答显示）**都是 opencode 主会话的 commit，不是 GPT 的**。
- 唯一由 GPT 写的生产代码是 `34c6f5f`（D4 处置一致性）。
- GPT 在 `193c6fc` 之前不写生产代码，只给裁定。把它当成 `triage/**` 的单写手会导致真正的 owner 被挡在门外。

## 2. 不属于本协作的路径

- `D:\IDEAprojects\langchain4j`：**不是** git 仓库，内容是与本项目无关的独立项目（java-ai-langchain4j、XiaoZhiAgent、xiaozhi-ui）。任何 agent 被指派去看那里都会得到「什么都没有」的结论。opencode 主会话的工作目录设为该路径，但全部提交都在 `D:\IDEAprojects\ai`，操作需显式指定目录。

## 3. 进行中的任务

| 任务 | Owner | 状态 |
| --- | --- | --- |
| D10 两步评估 + 孕产状态待确认 + 文案 + 文档 | opencode | 已完成（`57e67bc` / `41d6562` / `e36cb25` / `cbd2008`） |
| 合规拒答前端显示 | opencode | 已完成（`c150929`） |
| D11 三条裁定（明显出血 / 句号 / 紧邻否定保护） | opencode | 已完成（`af43242` / `b52a1bc` / `cd397c2`，P6，76/76） |
| 知识源抓取与召回复算 | 另一个 opencode | 进行中（曾见 `cli.mjs` / `verify.mjs` / `sources.json` 未提交） |
| 第三人 / 既往 / 跨轮事件隔离 | 待 GPT 裁定 | 需按主体与时间归属改造，属 Stage 2 |
| 孕产否定形式覆盖不全（`并非可能怀孕`） | 待 GPT 裁定 | `(?<!不)` 只覆盖紧邻的「不」 |
| A2 辅助模式入数据与红点漏检 | GPT | 已提交 `8649a6b`；P7，隔离构建 90/90 绿，待独立复核 |
| 无皮疹食物相关气道组合 | GPT | 已提交 `5b1572d`；P8，隔离构建 93/93 绿，待独立复核 |
| 胸部疼痛漏警、页面常驻提示压缩 | GPT | 本轮未提交，独立复核待完成；擅加的鼻部→全科映射、评分调整和知识文案已按用户纠正撤回，科室能力缺失不得靠新增症状硬编码补齐 |
| RAG 重设计：BM25 + embedding/Qdrant + 融合重排 + 评测 | GPT | 2026-10-03 真实百炼/Qdrant/rerank 与 8092 问诊接口已验证；离线 105 通过、1 外部用例跳过，前端构建通过。精度、LLM 未报告≠已否认措辞、患者端切换和独立复核仍未完成；详见 RAG_REDESIGN_2026-10-02.md |
| 后台知识管理、检索调试、AI 运行观测 | GPT | 功能已实现并在 5188 浏览器流程验证，前端 build 与四项后台测试通过；全量复验暴露已有会话排序失败，修复交接待用户答复。百炼 rerank 当前 HTTP_400:Arrearage，后台如实显示依赖阻断。独立复核待完成，见 ADMIN_AI_WORKSPACE_2026-10-03.md |
| 后台知识管理、检索调试、AI 运行观测 | GPT | 功能已实现并在 5188 浏览器流程验证，前端 build 与四项后台测试通过；全量复验暴露已有会话排序失败，修复交接待用户答复。百炼 rerank 当前 HTTP_400:Arrearage，后台如实显示依赖阻断。独立复核待完成，见 ADMIN_AI_WORKSPACE_2026-10-03.md |
| `RuleBasedTriageEngine:77`「第二十一条」条号 | opencode | 待降级为只引文件号+文意 |
| 百炼重排切换 qwen3.7-text-rerank | GPT | 真实开发/留出集与当前 5188 页面路径通过，阈值 0.5，运行服务已重启；glm-5.3 仍 Arrearage、全量回归仍有既有会话排序失败，未宣称全绿。详见 RERANK_MODEL_SWITCH_2026-10-03.md；待独立复核 |
| 聊天业务空间地址修正 | GPT | 已使用用户提供地址重启当前后端，glm-5.3 两轮真实问答 LIVE，记住两天病程；检索与后台页面复测通过。科室匹配、回答质量与已有消息排序仍需检查；见 CHAT_WORKSPACE_FIX_2026-10-03.md，待独立复核 |
| 轻量产品改造 | GPT | 用户已明确交接 catalog、患者资料新模块、会话排序与对应前端/测试，opencode 暂停这些范围。先修持久化消息顺序，后补数据管理和患者资料；README 与 LIGHTWEIGHT_DELIVERY_PLAN.md 记录边界与分阶段进度，不宣称全部完成 |
| 科室/医生/最小号源管理 | GPT | 已实现持久化新增/编辑/启停、容量约束和患者目录同步，浏览器闭环及全量回归通过；医生仅维护一个当前号源，仍为模拟。患者资料待下一批；见 CATALOG_MANAGEMENT_2026-10-03.md，待独立复核 |

### 已终止的待裁定项

- 「明显出血」应路由到哪条规则 —— 已由 GPT 裁定 1 解决，落地于 `af43242`
- 句号是否也阻断组合 —— 已由 GPT 裁定 2 解决（不阻断）
- 是否给 11 条第一步规则加紧邻否定保护 —— 已由 GPT 裁定 3 解决，落地于 `cd397c2`

## 4. 共享资源冲突

患者本人基础资料由 GPT 完成：本人 GET/PUT、MyBatis 持久化、版本冲突及统一患者导航；JDK17全量122项零失败、1外部跳过，前端构建与浏览器验证通过。不默认允许管理员浏览，不自动注入模型。见 PATIENT_PROFILE_2026-10-03.md；独立复核待完成。

`backend/target` 是共享目录。**同时跑 `mvn test` 会互相覆盖 surefire 报告**，且 `target/surefire-reports/` 会残留已删除测试类的旧报告，直接汇总会多算。并行前先确认没有人在跑。

`git add` 只用具体路径，**禁止 `-A` / `add .`** —— 本仓库常有多方未提交改动，一句 `git add -A` 就会把别人的在制品扫进自己的 commit。

## 5. 环境事实

- 基准 JDK `E:\JDK17\jdk-17.0.1`，须先设 `$env:JAVA_HOME`；默认 `java` 为 1.8，会报 class version 61。
- **不要用 JDK 25**（`D:\Elasticsearch\elasticsearch-9.3.3\jdk`）：Mockito 的 Byte Buddy 仅支持到 Java 23，mock 具体类会产生与业务无关的假失败。
- 本仓库基准为 **JDK 17**，写代码时注意 API 可用性。例：`Matcher.region(int,int)` 可用，JDK 20 的 `matcher(CharSequence,int,int)` 重载不可用。
- `mvn compile` 报 SUCCESS **不足以证明改动被编译**：增量编译可能读旧 target。改动后必须跑 `mvn clean test` 才作数。
- PowerShell 5.1 发中文请求体必须 `[System.Text.Encoding]::UTF8.GetBytes($json)`，否则正文变 `????`，所有安全规则会全部漏判。控制台回显中文同样会花屏，验证中文行为时不要依赖控制台输出，改用断言或写 ASCII 结果文件。
- `www.nhc.gov.cn` 对程序化请求返回 HTTP 412（WAF）。标 `manual` 人工读，不要伪装 UA 绕过。
