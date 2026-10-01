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

**如实声明**：早期 ACCEPTED 仅由本地 mock 端点补证；2026-10-01 18:49 和 18:53 已另外用百炼 `glm-5.3` 完成真实模型 ACCEPTED/LIVE 复验，见下节。这只证明该输入在当前账号、模型和演示数据下通过，不代表临床有效性或稳定性。密钥只作为进程环境变量注入，未写入仓库。

## 百炼 glm-5.3 真实复验（2026-10-01 18:49–18:53）

- 先用极简直连请求验证鉴权与正文：`model=glm-5.3`、`reasoning_effort=low`、`max_tokens=4096`，返回非空 `content`，`finish_reason=stop`。随后用当前项目后端进行端到端测试；直连成功本身不作为业务验收。
- 独立 8097 端口、内存 H2：输入“头晕三天，同时腹痛并反酸”，得到 `消化内科`、置信度 60、`grounded=true`、`modelStatus=LIVE`；调用日志为 `ACCEPTED/glm-5.3`（成功，4663 ms）和 `glm-5.3/LIVE`（成功，18782 ms）。该测试服务已停止。
- 原 8080 端口以相同模型重启，沿用原有数据目录；重启后核对既有 42 个会话和 3 条预约仍可见。新建合成测试会话 `f02f2492-51cb-4b67-9373-a2155e4c11f3`，同一输入得到 `消化内科`、置信度 70、`grounded=true`、`modelStatus=LIVE`；后台记录 `ACCEPTED/glm-5.3`（4995 ms）及 `glm-5.3/LIVE`（18232 ms）。该合成测试会话保留在演示数据中。
- 当前测试使用本地知识检索路径；本次**没有**重新证明 Qdrant 向量召回或真实医院数据质量。`glm-5.3` 强制思考，不能关闭；输出预算可用 `AI_MAX_TOKENS` 调整。耗时约 18 秒，尚未解决等待体验和并发成本问题。
- 验证：JDK 17、无 `AI_*` 环境变量运行 `mvn -q package` 通过（当前 Surefire 报告共 46 项、0 失败）；前端 `npm run build` 通过，本次未改前端。

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

历史结论更新：此前模型与额度组合未拿到真实 ACCEPTED；本次将预算配置化后，百炼 `glm-5.3` 在上述两次项目请求中通过。其他供应商/模型仍按历史记录视为未通过，不能据此推断已恢复。`glm-5.3` 不支持关闭思考，不能套用关闭思考的方案。

## 测试与构建

- 无任何 AI_* 环境变量：历史任务 A 阶段 `mvn test` 为 44 项全绿；本次 `mvn -q package` 的当前测试集为 46 项、0 失败
- 新增引擎测试：接受替换科室与置信度并展示依据；拒绝保持规则结果；紧急路径不咨询；未配置不咨询
- `frontend npm run build` 通过
- 演示请求体必须以 UTF-8 字节发送（PowerShell 5.1 字符串体会损坏中文，本次实测踩坑）
- 决策、解释、追问和一般信息的输出预算统一由 `AI_MAX_TOKENS` 控制（默认 4096，最小 256）；已按上节核验 `glm-5.3` 的实际响应、ACCEPTED 日志和 LIVE 状态。

## 边界（未完成/待外部）

- 决策仅覆盖"多候选主科室选择"这一个点；单候选、澄清追问、紧急分流不调用模型
- 临床有效性未审核；置信度为模型自报值，仅供展示，不参与任何分支判断
- 供应商额度、网络可用性依赖外部；本地降级路径已实测
- 真实模型的 ACCEPTED 已在单一合成症状用例上复验；尚未完成多症状覆盖、并发与长期稳定性测试

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
