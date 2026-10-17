# Agent 与工作流

## 业务边界

患者普通咨询使用一个受控只读 Agent，不是五个诊疗 Agent，也不是 MCP 客户端。安全规则、分诊结果保存和预约资格由后端控制。未接入真实 HIS，未完成临床审核。

## 代码职责

| 组件 | 职责 | 不负责 |
| --- | --- | --- |
| `TriageConversationService` | 身份、同会话并发保护、消息/分诊保存、会话状态 | 模型工具循环 |
| `TriageWorkflow` | 协调路由、普通咨询和分诊计算，返回类型化结果 | 数据库写入、预约提交 |
| `WorkflowRouter` | 复用原安全与追问策略，返回 `RouteDecision` | 新增医学词表、模型风险裁决 |
| `LangChainIntentExtractor` / `IntentValidator` | 意图抽取、Schema和患者原话来源校验 | 证明医学语义正确 |
| `SessionFactReducer` / `SessionFacts` | 来源验证、追加归并和保守检索投影 | 自动消解事件、清除原文风险 |
| `AgentWorkflowCoordinator` | 冻结工具作用域、检查新鲜度、采用实际检索快照 | 采用规划器自由文本作为答案 |
| `AgentRunner` / `AgentToolGateway` | 有界工具循环、严格参数、权限范围、预算和去重 | 写预约、审批知识 |
| `GuidanceAnswerService` | 普通咨询检索、复用准备好的证据、调用原回答与审核链 | 固定安全回复和合规裁定 |
| `RuleBasedTriageEngine` | 既有安全/追问策略、候选方向、分诊计算 | 事实持久化、模型自主预约 |

没有为了目录整齐重建所有业务模块。预约、知识、目录等继续按业务模块分层；现有引擎仍有历史策略与兜底文案，本次未假称完全解耦。

## 一轮执行

```text
身份和输入校验 → 保存患者消息 → TriageWorkflow / WorkflowRouter
  ├─ 急症或人工复核 → 原分诊安全路径，不进入Agent
  ├─ 需要咨询或补充 → Agent可选准备证据 → GuidanceAnswerService
  │                   → 原生成/引用检查/支持核对
  └─ 可进行评估 → 原分诊引擎
最后由会话服务保存回答、来源、状态和分诊版本。
```

路由判断顺序保持“立即就医 → 人工复核 → 是否需要补充 → 评估”。`RouteDecision` 是工作流分支，不是诊断或预约凭证。固定拒答和出血追问仍由原安全/合规策略产生，不交给模型改写。

## 事实归并

- `SessionFactReducer.merge` 接收带版本的旧投影和经校验的提案；新增来源必须来自当前输入中的USER消息，不能来自助手。
- 精确重复事件去重；新的否定不删除早先肯定事件。版本倒退或同版本改变事实会拒绝。
- 本人/他人、当前/既往/未知、肯定/否定/不确定保持分离，不猜测事件身份或临床同义词。
- 派生查询只采用本人、当前、肯定症状原词；同词同主体同时间分类有否定/不确定观察时不生成该词。提案有显式冲突时不生成任何派生症状词，仍可使用本轮完整原话检索。
- 当前工具输入仍只重建最近五轮/4000字符窗口的投影。来源校验后的观察另以版本化快照持久化，只有轮次完成后可见；它不是临床事实，也不自动将窗口外内容发送给模型。完整医学事件消歧和历史事实驱动追问尚未实现；来源校验不能证明模型正确理解了主体、时间和否定。
- 原安全检查继续读取患者原话累计历史，不消费此投影清除风险或赋予预约资格。

## 配置、失败与验证

### 离线意图与工具评分

`node tools/rag-eval/agent-quality-cli.mjs <run.json> <reviewed-labels.json>` 不联网、不调用模型，仅输出技术计数。运行文件 `schemaVersion:1` 包含 `dataset`（同版本、id、DEVELOPMENT/HOLDOUT、cases）、`configuration`（model、promptVersion、policyVersion）与 `results`。每个数据项保存id和input.messages（id/role/content，最多5轮/4000字符）；结果保存id、唯一traceId、inputSha256、COMPLETED/UNAVAILABLE/INVALID、intents、tools（name/success）、elapsedMs、可空inputTokens/outputTokens和tokenUsageSource。工具名仍限现有三个只读工具。指纹采用工具导出的 `agentFingerprint` 对排序键后的JSON作SHA-256，绑定输入、完整数据集和完整运行。

标签文件同版本，包含datasetSha256、runSha256、reviewer（id及AUTHOR_ENGINEERING/INDEPENDENT_ENGINEERING）与cases（id、期望intents、期望tools）。每项必须由评审者填写，不自动从模型答案生成；“独立”是评审者声明，程序不能认证其身份。原始输入与标签只留本机忽略目录，不提交患者内容。修改输入、Prompt版本、输出或配置后，旧标签不能复用。

输出意图/工具集合完全一致率、micro Precision/Recall/F1、缺运行/未标注/失败数、评审样本完成率、全部运行p50/p95和供应商声明用量覆盖数。重复查询同工具计多次执行，但工具选择集合只计一次；选择正确与执行成功分开。失败不变成“空集合正确”，也不悄悄消失，已有标签却缺运行的项目仍进入完成率分母；缺失用量不作0。空分母为null。此工具尚不评价症状事件的语义归属、临床正确或真实账单；目前测试是合成工程夹具，没有多场景实测质量成绩。

回答生成由 `ContextAssembler` 整条组装系统约束、引用资料和最近五轮对话（对话累计≤4000字符）。证据按完整引用JSON≤4000字符选择，保留原引用ID和哈希，不能截掉条件句；总预算不足时先移除最早完整对话轮，再移除末位完整证据。本轮问题和系统约束不可裁；无法容纳时明确 `CONTEXT_BLOCKED`，不是检索未命中或供应商故障。不得把所有证据裁掉后冒称无资料。

兼容的 `explainWithServices`、`guide`、`guideGeneral` 入口同样在创建模型客户端前组装和检查预算；本地预算阻断保留 `PROMPT_PREPARATION`、不记录供应商调用用量。兼容入口未取得版本化检索快照时，其检索状态为UNKNOWN，不猜测未命中。

`AI_CONTEXT_INPUT_TOKEN_BUDGET` 默认65536，允许512–262144。此处以UTF-8字节加消息开销作保守估算，**不是模型分词器、供应商实际Token或账单**，也不保证适配所有模型的上下文上限。回答核对请求在发送前独立检查同一预算，超限不请求供应商；阶段明细可查看估算和整条移除数量。意图抽取同样检查完整Schema和原话JSON，超限保留来源、不静默裁剪，返回固定`INTENT_CONTEXT_*`并回退工作流；这类本地阻断不计供应商请求用量。成功组装记录CONTEXT/INTENT_EXTRACTION估算，实际用量仍取供应商响应。此预算当前覆盖回答生成/核对、意图抽取及旧叙述入口，不代表工具规划和全轮成本预算均已统一。

日常主配置启用真实模型及Agent，OFF回退、SHADOW影子抽取均可显式选择。默认最多3次规划、4次只读执行、单次规划12秒、规划循环25秒；这不是全轮延迟承诺。状态/语料/目录漂移、非法调用、超时、循环或模型失败时回退原工作流。

抽取和只读工具规划默认分别使用不输出思考链的短请求；患者回答生成仍按 `ai.enable-thinking` 配置。两阶段可分别用 `AI_INTENT_ENABLE_THINKING`、`AI_AGENT_PLANNING_ENABLE_THINKING` 调整。这个配置只控制供应商请求，不改变引用、风险或预约检查。原8081实测意图抽取在8秒截止，故不能以普通回答成功冒充Agent完成。

只读工具的本轮参数Schema列出已验证的原话查询及可用科室，减少模型提出无权使用的参数；`AgentToolGateway`仍逐项验证实际参数。越界参数记为`TOOL_BLOCKED`并回退，执行器故障记为`TOOL_FAILED`。两者都不授权预约或医学风险结论。

工具选择按已验证意图和当前请求收窄：一般咨询只暴露医学检索；明确找科室或医生时才暴露对应目录工具。一次成功的医学检索结果回传后，不再向下一轮模型提供同一检索工具；模型必须读取结果并返回`DONE`，否则回退。无待选工具时仍可传递既有工具结果。真实线上审计需出现`Agent执行/COMPLETED`才能称Agent完成；仅有模型返回`TOOLS`或最终回答`LIVE`不够。

兼容接口的续轮仍带上刚执行工具的函数定义，并以`tool_choice=none`禁止新增工具请求；服务端同时验证返回不能含`tool_calls`。规划失败只记录异常类型/HTTP状态映射出的固定失败码，不记录供应商错误正文或思考文本。

收到工具结果后的结束轮再加入明确的“仅返回DONE”控制消息，输出上限64 tokens；只有独立的`DONE`标记才算规划完成。其他停止文本记`INVALID/STOP_NON_DONE`，长度等非正常结束记固定`FINISH_*`码，不保存模型自由文本或把它作为患者回答；旧工作流仍可保守接管。该修正在百炼配置下通过一次合成普通咨询闭环复验，不能据此宣称多场景稳定或临床适用。

意图抽取失败通过现有异常分类器记录固定错误码，区分 `INTENT_MODEL_TIMEOUT`、`INTENT_MODEL_AUTH_FAILED`、`INTENT_MODEL_RATE_LIMITED` 和网络等错误；不记录供应商正文、异常消息或密钥，不自动重试。429只代表限流响应，不能单凭此码判断额度耗尽。抽取失败不进入工具规划，后续工作流生成的回答仍须独立通过引用与支持核对；工具成功或审计关联成功不代表答案通过。

测试入口：`WorkflowRouterTest`、`TriageWorkflowTest`、`SessionFactReducerTest`、`GuidanceAnswerServiceTest`、`AgentWorkflowCoordinatorTest`、`AgentRunnerTest` 和 `AgentFunctionCallingTransportTest`。完整运行方式为JDK17下 `cd backend; .\mvnw.cmd test`（Linux/macOS：`./mvnw test`），前端 `npm test` 与 `npm run build`。

本地HTTP协议测试只证明适配器消息传输。2026-10-11 在百炼配置下的一次合成普通咨询已观察到模型选工具、工具结果消费、`Agent执行/COMPLETED` 和独立回答核对 `PASSED`；这是单样本集成验证，不证明稳定性、临床准确性或性能收益。患者页面的服务端待确认预约动作已接入，但不是模型生成动作，Agent仍无预约写权限。来源校验后的模型观察可跨轮持久化，并仅在轮次完成后发布；历史观察不会自动进入当前工具/模型窗口。完整事件消歧、历史事实驱动追问、完整意图路由与多场景真实模型验收仍待完成。
