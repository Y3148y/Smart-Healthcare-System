# LLM 结构化分诊决策实测记录（2026-10-01）

定位：任务书 A——在多候选场景下让 OpenAI 兼容模型输出**结构化路由决策**（JSON），由确定性规则护栏校验，任何异常回退到规则决策。安全评估、风险等级、阈值、知识检索仍全部由服务端规则控制，模型不能触碰。接续 [Qdrant 实测记录](QDRANT_LIVE_VERIFICATION_2026-09-30.md)。

## 实现位置

| 文件 | 职责 |
| --- | --- |
| `triage/infrastructure/llm/StructuredDecisionModel.java` | 构建提示词、调用模型、解析 JSON、执行护栏；`enabled()/propose()`，产出 `Proposal(status, decision)` |
| `triage/infrastructure/demo/RuleBasedTriageEngine.java` | `triage()` 中在规则算出候选后挂钩：仅当 `!emergency && 候选>1 && enabled()` 才调用；接受则替换科室/置信度/候选理由，并记录 CallLog |
| 配置 | 复用 `ai.mode/api-key/base-url/model/timeout-seconds`；输出预算可用 `AI_MAX_TOKENS` 调整，默认 4096 |

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

**如实声明**：ACCEPTED 路径当前由本地 mock 端点补证（脚本见附录），非真实模型输出。embedding 额度独立，Qdrant 混合检索不受影响。

## 真实供应商探测（2026-10-01 17:00-17:50，未成功拿到 ACCEPTED）

换供应商后的实测记录，全部失败原因已定位到"额度/限流/思考型模型"，**没有一条是业务代码缺陷**：

| 供应商 | 配置 | 实测结果 |
| --- | --- | --- |
| 百炼 chat | `qwen-plus`/`turbo`/`max`、`deepseek-v3`、`glm-4.6`、`qwen3-max-preview` | 全部 `AllocationQuota.FreeTierOnly`，账号级免费额度耗尽，换模型名无效 |
| 百炼 embedding | `qwen3.7-text-embedding` | ✅ 正常返回 1024 维，Qdrant 混合检索不受影响 |
| 商汤（旧端点） | `https://api.sensenova.cn/compatible-mode/v1` | `code 7 Forbidden`；该 host 只有这一条 chat 路由，其余路径 `no Route matched`——**这个 key 不适用于该端点** |
| 商汤（正确端点） | `https://token.sensenova.cn/v1`，模型清单：`glm-5.2`、`deepseek-v4-flash/pro`、`deepseek-v4.1-flash`、`deepseek-flash`、`kimi-k3`、`sensenova-6.8-flash-lite`、`sensenova-u1-fast`、`sensenova-u1.5-lite` | key 鉴权通过 ✅；`glm-5.2` 直连 891ms 返回 ✅ |
| 商汤 `glm-5.2` 接入后 | 应用内决策+解释 | 决策 `REJECTED/glm-5.2 ms=3428`、解释 `FALLBACK`：响应里 `content` 为空、409 字符全在 `reasoning_content`、`finish_reason=length`——**强制思考型模型，输出预算被思考吃光** |
| 商汤 `deepseek-v4-flash` | 直连 220/900 tokens | 直连 content 正常（76/65 字符）✅；接入应用后决策与解释同时报 `OpenAiHttpException 429 inference exceeds tpm/rpm limit`——**探测并发把 TPM/RPM 配额打爆** |
| 商汤 `deepseek-v4-pro`、`kimi-k3` | 直连 | 同样触发 TPM/RPM 限流 |
| 商汤 `deepseek-v4.1-flash`、`sensenova-u1.5-lite` | 直连 | `not available in the current token plan` / `model is not found` |
| 本地 Ollama `qwen3.5:4b` | `http://127.0.0.1:11434/v1` | 决策 `REJECTED/qwen3.5:4b ms=30301`、解释 `FALLBACK`：消息字段是 `reasoning`（非 `reasoning_content`），`max_tokens=1024` 与 `think:false` 均无效，`content` 恒为空、`finish_reason=length`——**同样是强制思考型** |

结论与下一步（未做）：真实模型的 ACCEPTED 复验需要先解决"强制思考型模型"这一类供应商，可选路径是①把决策/解释的 token 预算提到 4096 以上并允许配置化；②按供应商关闭思考（GLM 系 `extra_body.thinking.type=disabled`、Qwen 系 Ollama `/api/chat` 的 `think:false`），需要能读到 `reasoning_content` 之外的输出字段；③换用确认非思考型的模型再复验。护栏与回退逻辑本身已由 44 项测试锁定，不依赖这三项选择。

## 测试与构建

- 无任何 AI_* 环境变量：`mvn test` **44 项全绿**（30 基线 + 10 `StructuredDecisionModelTest` + 4 `RuleBasedTriageEngineTest` 新增）
- 新增引擎测试：接受替换科室与置信度并展示依据；拒绝保持规则结果；紧急路径不咨询；未配置不咨询
- `frontend npm run build` 通过
- 演示请求体必须以 UTF-8 字节发送（PowerShell 5.1 字符串体会损坏中文，本次实测踩坑）
- 后续复验将决策、解释、追问和一般信息的输出预算统一改为 `AI_MAX_TOKENS`（默认 4096，最小 256）。这只解决预算可调问题，**尚未证明**任何云端模型在本次配置下返回 `ACCEPTED`；实际是否可用仍须以当前账号额度、响应正文和应用调用日志核验。

## 边界（未完成/待外部）

- 决策仅覆盖"多候选主科室选择"这一个点；单候选、澄清追问、紧急分流不调用模型
- 临床有效性未审核；置信度为模型自报值，仅供展示，不参与任何分支判断
- 供应商额度、网络可用性依赖外部；本地降级路径已实测
- 真实模型的 ACCEPTED 仍未复验（见上节），根因是额度/限流/强制思考型模型，不是代码逻辑

## 讲解三问

1. **模型能推翻规则吗？** 不能。紧急分流不咨询模型；风险等级由规则计算；模型只在白名单内选科室，任何违规整体拒绝回退。护栏是纯函数，10 个单元测试逐条锁定。
2. **回退有哪几种？** SKIPPED（未配置）、REJECTED（护栏拒绝）、ERROR（供应商异常）、以及紧急路径的永不咨询——四种状态在 CallLog 里都有记录，全部实测过。
3. **怎么接真实模型？** 配 `AI_MODE=openai-compatible`、`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL`，思考型模型可再调 `AI_MAX_TOKENS`；与解释文本共用一套配置。配置成功不等于业务验收通过，仍需核对响应正文非空、`ACCEPTED/<模型>` 和 `modelStatus=LIVE`。此前测试账号的额度/限流结论只代表当时实测，不代表当前账号状态。

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
