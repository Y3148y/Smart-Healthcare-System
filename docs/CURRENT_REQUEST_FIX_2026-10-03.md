# 当前请求与历史症状分离

## 复现与原因

修复前，第一轮“咳嗽两天，请给我开药”被确定性合规拒答；第二轮“我只想挂号，咳嗽两天，没有胸痛，也没有呼吸困难”仍被同一拒答拦住。服务把历次USER文本拼接，Engine对拼接文本判断请求类型，导致旧开药意图永久保留。此路径没有调用LLM，不是模型故障。

## 本批修改

- `CurrentRequestIntent.restricted/latest`：从历史消息取最近USER请求；保留原合规词表，集中到domain策略。仅明确撤回“不要/不用/不需要/无需开药”等请求时忽略该短语；同句重新索要处方仍拒绝。该策略是词表判断，不是语义理解，不宣称完整意图识别。
- `TriageEngine.needsClarification(symptoms,currentRequest)`：增加两参数边界，默认兼容其他实现。
- `TriageConversationService.send`：传递本轮请求内容。历史原文仍留存；累计症状仍用于危险信号、检索和分诊。
- `RuleBasedTriageEngine.needsClarification/clarificationPrompt/triage`：当前请求决定合规拒答和明确挂号意图；不再对所有历史请求进行同一意图判断。
- `CurrentRequestFlowTest`：旧处方请求不污染后续挂号、明确撤回但新请求仍拦截、重新开处方拒答、撤回开药不抹掉胸痛/呼吸困难闸门。
- `scripts/test-current-intent-live.cjs`：真实服务两轮、LIVE/RAG、历史原文/消息顺序/刷新、模拟预约和相同幂等键返回原预约。

不新增症状→科室映射，不改变医学安全规则、医生目录、处方拒答文案或预约扣减代码。不把demo验证代替真实模型调用。

## 验证记录（2026-10-03）

JDK17，无AI环境变量，在`.codex-intent-tests`隔离副本执行 `mvn clean test -q -DforkCount=0`：130项，129通过、1外部测试跳过、零失败/错误。副本包含原来未提交的安全修正，本任务commit不包含它们。前端`.codex-rag-ui`的`npm run build`通过，本批未修改UI。

当前8081后端已重启，沿用原DB、glm-5.3工作空间、百炼embedding/rerank/Qdrant配置。5188真实接口复验：开药请求POLICY_REFUSAL；下一轮“不开药只挂号”回答LIVE、知识命中3条、呼吸内科和模拟医生、版本1；刷新保持USER/ASSISTANT/USER/ASSISTANT与原文；模拟预约成功，两次同幂等键返回相同预约ID。验证耗时6410ms（从第二轮开始至刷新和两次预约请求完成，并非纯模型延迟）。

合成会话ID `f6e428eb-65db-4433-a35b-0e38cb89a793`，lisi演示账号；保留会话与模拟预约，不代表真实患者或真实医院预约，不删旧数据。

## 未完成与剩余风险

修复前另测：流鼻涕走LIVE_UNGROUNDED、无依据无预约，耗时35503ms；咳嗽明确挂号走LIVE与3条依据，约6055ms。本批没有消除一般回答的长等待。

历史症状仍累计拼接，不解决第三人/既往/跨轮不同事件归属，也不允许取消历史危险信号。既有症状科室映射、多科室排序、一般问答意图和按需追问仍需分别检查。没有对应能力的症状不能靠新增词表强行落到全科。本批不宣称整个问诊质量任务完成，独立复核待其他owner执行。
