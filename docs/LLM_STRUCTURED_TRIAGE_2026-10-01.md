# LLM 结构化分诊决策实测记录（2026-10-01）

定位：任务书 A——在多候选场景下让 OpenAI 兼容模型输出**结构化路由决策**（JSON），由确定性规则护栏校验，任何异常回退到规则决策。安全评估、风险等级、阈值、知识检索仍全部由服务端规则控制，模型不能触碰。接续 [Qdrant 实测记录](QDRANT_LIVE_VERIFICATION_2026-09-30.md)。

## 实现位置

| 文件 | 职责 |
| --- | --- |
| `triage/infrastructure/llm/StructuredDecisionModel.java` | 构建提示词、调用模型、解析 JSON、执行护栏；`enabled()/propose()`，产出 `Proposal(status, decision)` |
| `triage/infrastructure/demo/RuleBasedTriageEngine.java` | `triage()` 中在规则算出候选后挂钩：仅当 `!emergency && 候选>1 && enabled()` 才调用；接受则替换科室/置信度/候选理由，并记录 CallLog |
| 配置 | 复用 `ai.mode/api-key/base-url/model/timeout-seconds`，无新增配置项 |

## 护栏与回退（全部有测试）

| 约束 | 行为 | 违反后果 |
| --- | --- | --- |
| G0 紧急分流 | `stopRoutineFlow` 时完全不调模型，`SAFETY_RULE` 原样 | 永不咨询（测试断言） |
| G1 科室白名单 | 只能选规则候选 ∪ {全科医学科} | 整体拒绝 → 规则决策 |
| G2 置信度 [35,85] | 超界拒绝（防过度自信与无意义值） | 整体拒绝 |
| G3 依据安全 | 空/超 160 字/含诊断、确诊、用药、剂量、急诊字样 | 整体拒绝 |
| G4 输出形状 | JSON 缺字段、非对象、坏 JSON、非白名单 | 整体拒绝 |
| G5 未配置 | demo 或无密钥：`SKIPPED`，零网络调用 | 规则决策原样 |
| G6 供应商异常 | 超时/额度/连接失败：`ERROR`，打 WARN | 规则决策原样，无 5xx |

风险等级（普通/尽快就医/紧急/多科室参考）不由模型给出：紧急由规则独占，其余沿用规则计算，因此模型无法升格或降级风险。

## 回退四态实测（2026-10-01）

| 状态 | 场景 | 实测结果 |
| --- | --- | --- |
| ACCEPTED | 本地 mock OpenAI 兼容端点（额度耗尽后补证） | `DEPT=消化内科`、`CONF=70`、`STATUS=LIVE`、候选理由=LLM 依据、CallLog `ACCEPTED/mock-local ok=true ms=562`，风险仍为"多科室参考" |
| ERROR | 真实百炼 `qwen-plus` | `AllocationQuota.FreeTierOnly`（key 免费额度账号级耗尽）→ CallLog `ERROR/qwen-plus ok=false` → 规则决策兜底，评估正常落库（`DEPT=全科医学科 CONF=55 GROUNDED=true`），会话无中断 |
| ERROR | mock 返回毫秒时间戳（本次排障发现的 DTO int 溢出） | 同样 `ERROR` → 回退正常——解析异常也被 G6 兜住 |
| REJECTED | 单元测试 10 例（白名单/置信度/坏 JSON/不安全依据等） | 全部按设计拒绝 |

**如实声明**：ACCEPTED 路径当前由本地 mock 端点补证（脚本见附录），非真实模型输出；真实模型的 ACCEPTED 需在百炼控制台充值或关闭"仅免费额度"后，用同一会话输入一句话复验（预期 CallLog 出现 `ACCEPTED/qwen-plus`）。embedding 额度独立，Qdrant 混合检索不受影响。

## 测试与构建

- 无任何 AI_* 环境变量：`mvn test` **44 项全绿**（30 基线 + 10 `StructuredDecisionModelTest` + 4 `RuleBasedTriageEngineTest` 新增）
- 新增引擎测试：接受替换科室与置信度并展示依据；拒绝保持规则结果；紧急路径不咨询；未配置不咨询
- `frontend npm run build` 通过
- 演示请求体必须以 UTF-8 字节发送（PowerShell 5.1 字符串体会损坏中文，本次实测踩坑）

## 边界（未完成/待外部）

- 决策仅覆盖"多候选主科室选择"这一个点；单候选、澄清追问、紧急分流不调用模型
- 临床有效性未审核；置信度为模型自报值，仅供展示，不参与任何分支判断
- 供应商额度、网络可用性依赖外部；本地降级路径已实测

## 讲解三问

1. **模型能推翻规则吗？** 不能。紧急分流不咨询模型；风险等级由规则计算；模型只在白名单内选科室，任何违规整体拒绝回退。护栏是纯函数，10 个单元测试逐条锁定。
2. **回退有哪几种？** SKIPPED（未配置）、REJECTED（护栏拒绝）、ERROR（供应商异常）、以及紧急路径的永不咨询——四种状态在 CallLog 里都有记录，全部实测过。
3. **怎么接真实模型？** 配 `AI_MODE=openai-compatible`、`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 即可，与解释文本共用一套配置；当前 key 对话额度耗尽，恢复后无需改代码。

## 附录：mock 复验脚本（本次验收所用）

```js
// node mock-openai-decision.js  然后 AI_BASE_URL=http://127.0.0.1:8099/v1/ AI_MODEL=mock-local
const http = require('http');
http.createServer((req, res) => {
  let data = '';
  req.on('data', c => data += c);
  req.on('end', () => {
    const content = data.includes('只输出一个 JSON 对象')
      ? JSON.stringify({department: '消化内科', basis: '以反酸和腹痛为主要表现', confidence: 70})
      : '根据你描述的症状，可先分别咨询神经内科或消化内科；如症状加重请及时线下就医。';
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({id: 'chatcmpl-mock', object: 'chat.completion',
      created: Math.floor(Date.now() / 1000), model: 'mock-local',
      choices: [{index: 0, message: {role: 'assistant', content}, finish_reason: 'stop'}],
      usage: {prompt_tokens: 120, completion_tokens: 40, total_tokens: 160}}));
  });
}).listen(8099, '127.0.0.1');
```

注意 `created` 必须是**秒级**时间戳（毫秒会超出响应 DTO 的 int 范围——本次实测踩坑）。
