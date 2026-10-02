# 预临床已知问题登记（未经临床审核）

> **性质**：本文登记的是**已确认的代码缺陷与未决问题**，不是待办愿望清单。
> **定级**：本系统为未经临床审核的原型。下列任何内容都未获得临床签署。
> **关联**：[交接文档](HANDOVER_TRIAGE_SAFETY_2026-10-02.md) 提供规则表、缺口清单与执行计划；本文只维护缺陷本体。
> **维护规则**：修复任一项时，将该条标记为 `已修复` 并注明 commit，**不要删除、不要重编号**——编号是稳定标识符，删除会打断本文与交接文档的交叉引用。

---

## 零、已修复

| 编号 | 摘要 | 修复 commit |
| --- | --- | --- |
| D1 | 合规拒答被记成普通追问引导，审计链断裂 | 见下方 D1 |
| D2 | 两个测试对"脸肿"给出虚假信心 | 见下方 D2 |

---

## 一、已确认缺陷

### D1｜已修复｜`prescriptionRefusalResult` 曾不可达，合规审计链缺失

- 位置：`backend/src/main/java/com/aihospital/triage/infrastructure/demo/RuleBasedTriageEngine.java`
- 原成因：
  1. `needsClarification()`（L63）对诊断/处方意图返回 `true`，`TriageConversationService:76` 因此走 `clarificationPrompt` 分支；
  2. `clarificationPrompt` 另有一份**独立**的拒答实现，其 CallLog 写成 `purpose="预问诊引导"`、`success=false`；
  3. `triage()` 的拒答分支条件是 `if (!emergency && hasDiagnosisOrPrescriptionIntent(...))`。由服务层分流可证：能进入 `triage()` 且非紧急时，`needsClarification` 必为 false，而该方法对拒答意图恒返回 true——故分支不可达。
- 原后果：唯一正确的 `合规拒答` CallLog 永不执行；患者实际路径上的拒答在管理端显示为普通追问引导且 `success=false`。
- 修复：抽出 `recordComplianceRefusal(...)` 作为唯一审计入口，两个分支共用；`clarificationPrompt` 改为发出 `purpose="合规拒答"`、`model="POLICY_REFUSAL"`、`success=true`、`tools=[]`。
- 保留说明：`prescriptionRefusalResult` **不删除**。它在服务层不可达是**期望属性**，作为纵深防御保留——若将来有人改动追问规则，拒答不能因此漏进普通路由。已在代码注释中写明。
- 回归测试：`complianceRefusalIsAuditedAsComplianceAndNeverAsOrdinaryGuidance`，关键断言是"不存在 `purpose=预问诊引导` 且 `model=POLICY_REFUSAL` 的记录"，直接编码原 bug。

### D2｜两个测试对"脸肿"给出虚假信心

- 位置：
  - `backend/src/test/java/com/aihospital/triage/RuleBasedTriageEngineTest.java:65`（测试名 `expandedEmergencySignalsAreUrgent`）
  - `backend/src/test/java/com/aihospital/TriageConversationTests.java:192`（测试名 `safetyWarningDoesNotWaitForFollowUp`）
- 成因：两处输入均含 `吞咽不了` / `喘不上气`，**仅**因此通过。`脸`、`肿` 不在任何规则表达式中。
- 证据：用户 live 实测"智齿发炎，我的脸都肿起来了"未触发任何安全信号、无红色警示、无评估。
- 后果：测试名暗示"面部肿胀已被覆盖"，会掩盖后续回归。
- 修复方向：改为**单独**断言"脸肿"；如需保留原测试，另加不依赖呼吸道短语的独立用例。

### D3｜`ER-PREGNANCY-001` 永不可达

- 位置：规则 `TriageSafetyPolicy.java:31`；入口校验 `TriageConversationTests.java:37`、`unsupportedPopulationCannotStartStandardTriage`（L52-57）
- 成因：建会话硬性要求 `notPregnantConfirmed: true`，系统在进入分诊前即排除孕产。
- 矛盾：排除孕产的同时携带孕期急诊规则，等于两条互斥策略并存。回答"确实怀孕"的患者在分诊前被拒；回答"未怀孕"的孕产患者才会触发该规则。
- 现实场景影响：患者会直接陈述"我怀孕了"，此时系统无孕周采集、无产科目录、无产科分诊逻辑。
- 修复方向：**待裁定**（见第三节 Q2）。不得单方面删除规则——那会移除一类高危表现的兜底。

### D4｜处置矛盾：`待补充信息` 同时挂可预约医生

- 位置：`RuleBasedTriageEngine.java:179-184`（挂医生）、`L199-202`（`confidence < 60` → `待补充信息`）
- 成因：
  1. 只要 `!emergency` 就查询并挂载医生，不看处置是否可预约；
  2. 会话状态按"是否存在评估"置为 `已完成分诊`，与 `riskLevel` 无关。
- 证据：用户 live 实测"流鼻涕 → 无发热鼻塞 → 我要挂号"得到 `department=全科医学科`、`riskLevel=待补充信息`、`confidence=55`、`grounded=true`、会话 `已完成分诊`、有可预约医生且预约成功。
- 放大因素：四处鼻部专用硬编码——`NASAL_SYMPTOM`(L25)、`bookingFallbackCandidates`(L234-237)、`hasAffirmedNasalSymptom`(L239-248)、`guidedFallback` 鼻部分支(L264-270)。
- 测试固化问题：`TriageConversationTests.java:308-328`（`explicitBookingForNasalSymptomsCreatesGroundedGeneralMedicineRecommendation`）**把该 bug 写成了期望值**。
- 修复方向：处置不可预约时不挂医生；会话状态与 `riskLevel` 对齐；删除鼻部硬编码；重写上述测试。

### D5｜`humanReviewRecommended` 是死字段

- 位置：计算于 `TriageSafetyPolicy.java:63`（作为第 4 个构造参数，即 `humanReviewRecommended`），断言于 `RuleBasedTriageEngineTest.java:89`
- 成因：`Models.SafetyAssessment`（`shared/model/Models.java:16-18`）第 4 个字段是 `humanReviewRecommended`，但**全库无任何生产代码读取**。
- 后果：URGENT（`UR-TRAUMA-001`/`UR-FEVER-001`/`UR-PAIN-001`）实际不触发任何人工接管。
- 复核命令：`git grep -n "humanReviewRecommended"`（应只命中定义、赋值与测试断言）
- 修复方向：**待裁定**（见第三节 Q1）。接通它会改变现有预约行为。

### D6｜测试套件绑定 demo 模式，live 路径无法在 CI 验证

- 位置：`TriageConversationTests.java:297` 断言 `provenance.modelStatus == "DEMO"`
- 后果：设 `AI_MODE=openai-compatible` 会使该套件失败。
- 说明：这是有意的严格断言（demo 模式不调外部模型），但代价是 live 行为无自动化覆盖。
- 修复方向：若要覆盖 live 路径，需分离"模式无关断言"与"模式相关断言"，或引入显式 profile。**非紧急。**

### D7｜`UR-FEVER-001` 门槛与 NICE 指南冲突

- 位置：`TriageSafetyPolicy.java:33`，表达式 `持续高热|高烧不退|体温.{0,3}(39|40)`
- 冲突：NICE `NG253` §1.1 指出脓毒症 *"may not have a high temperature"*。现有规则把 ≥39℃ 当作门槛，结构上与该指南相反。
- 附加缺口：未覆盖低体温、寒战、皮肤冰冷。老年感染者低体温常见。
- 修复方向：补低体温/寒战表达，去掉"必须 ≥39℃"这一门槛。
- 约束：无临床团队无法仲裁阈值取舍。修改时必须附出处 + `未经临床审核` 标记，并升 `POLICY_VERSION`。

---

## 二、结构性缺口（非单点缺陷，无法靠加规则解决）

| 缺口 | 影响 | 状态 |
| --- | --- | --- |
| **生命体征无采集通道** | 心率、呼吸频率、尿量、血压全部不可用，NICE/NHS 脓毒症判据中依赖这些指标的部分无法实现 | 暂缓（§4 生命体征） |
| **无年龄采集** | `CN-ADULT-ONLINE-TRIAGE` 仅靠前端 `adultConfirmed` 声明保证，无法核实 | 暂缓 |
| **无孕周采集 / 无产科目录** | 孕产无法进入分诊，只能安全转出 | 待裁定（Q2） |
| **自由文本优先，缺临床层级提问** | 中文症状表述组合无法穷举；自由文本模式下结构性漏判不可避免（如 D2） | 暂缓（§3 固定问句） |
| **临床表述映射未经审核** | 指南原文为英文，中文表述由本项目自行映射，是当前最缺留痕的环节 | 长期缺口 |

---

## 三、待裁定问题（**无结论，禁止自行决定**）

> 撰写者曾自行给出建议，被 owner 否决并要求交 GPT 裁定。以下三问截至本文写作时**均无结论**。

### Q1｜URGENT 是否应阻断在线预约？

- 背景：URGENT 现状给出可预约号源。`UR-PAIN-001`（剧烈腹痛）→ 消化内科普通号；腹痛可能是 AAA / 异位妊娠 / 肠梗阻。另：入口已强制非孕产（`D3`），异位妊娠按现规约不可达。
- 选项：(a) 阻断预约 + 转人工（接通 `humanReviewRecommended`，属还原既有设计意图）／ (b) 维持可预约，仅强化文案 ／ (c) 按类别区分：可门诊处理的保留，感染/出血/剧烈疼痛类阻断
- 阻塞项：`D5`

### Q2｜孕产矛盾（`D3`）如何处理？

- 选项：(a) 建孕产筛查问句 → 产科/急诊分支（需改会话状态机 + 前端）／ (b) 明确不支持孕产，删 `ER-PREGNANCY-001` + 明确拒答话术 ／ (c) 中途会话加确定性孕产断言门：不出科室/医生/预约，固定转出话术 + 人工入口
- 阻塞项：`D3`、Stage 2 全部

### Q3｜下一轮做到哪一层？

| Stage | 范围 | 改交互 | 依赖裁定 |
| --- | --- | --- | --- |
| 0 | 提交在手补丁 + 补全测试断言 + 本文 | 否 | 否 |
| 1 | 纯 bug 修复（`D1`/`D2`/`D4`） | 否 | 否 |
| 1b | 接通 `humanReviewRecommended` | 否 | **Q1** |
| 2 | P0 红旗规则 + 语料集 | 否 | **Q2** |
| 3 | 固定高危问句（4 轮） | 是 | Q1+Q2 |
| 4 | 生命体征采集或书面声明 | 是 | Q1+Q2 |

---

## 四、工具链与环境事实

| 事项 | 事实 |
| --- | --- |
| 可用 JDK | **仅** `D:\Elasticsearch\elasticsearch-9.3.3\jdk`（JDK 25）。需先设 `$env:JAVA_HOME` |
| 默认 `java` | 1.8.0_221，`mvn test` 报 `class version 61` |
| `D:\FinallShell\finalshell\jre` | 缺 `com.sun.net.httpserver`，`QdrantSemanticIndexTest` 抛 `NoClassDefFoundError` |
| 本机是否存在 JDK 17 | **否**。`AGENTS.md` 原文要求 JDK 17，无法满足，已修正为 JDK 25 |
| 测试基线 | **50 项 / 9 个测试类全绿**（无任何 `AI_*` 环境变量） |
| 计数陷阱 | `target/surefire-reports/` 残留已删除测试类的旧报告（`LocalRetrieveProbeTest`、`SourceEncodingProbeTest`），直接汇总会多算 2 项。统计时按文件修改时间过滤 |
| PowerShell 中文 | 请求体必须 `[System.Text.Encoding]::UTF8.GetBytes($json)`，`ContentType` 带 `charset=utf-8`。否则正文变 `????`，**所有安全规则全部漏判** |
| 管理端鉴权 | 必须 `Authorization: Bearer <token>`。漏 `Bearer ` 前缀会被误判为 `JwtService` 缺陷 |
| 数据核查 | 勿用 H2 文件 mtime 或应用日志推断有无写入：只有登录请求被记录。用管理端 API 查询 |

---

## 五、已作废的结论（勿据以决策）

| 旧结论 | 出处 | 状态 |
| --- | --- | --- |
| 百炼 chat 额度耗尽（`AllocationQuota.FreeTierOnly`） | `HANDOVER_2026-10-01.md:23` | **已作废**。`glm-5.3` live 调用实测成功 |
| 测试基线 44 项 | `HANDOVER_2026-10-01.md:50` | **已作废**，现为 50 项 |
| `stopRoutineFlow` 含 URGENT | 撰写者口头表述 | **错误**。仅含 EMERGENCY；`humanReviewRecommended` 才含 URGENT |
| 11 条规则全为 EMERGENCY | 撰写者口头表述 | **错误**。8 EMERGENCY + 3 URGENT，另有 1 条派生 = 共 12 个规则码 |
| 规则+测试可得出召回率 | 撰写者口头承诺 | **错误**。规则与测试同源，构造上必然 100%，无信息量。禁止命名为召回率/敏感度/安全指标 |

---

**本文档不构成临床认可。** 所列缺陷的修复方案与新增规则均需具备资质的临床人员审核后，方可用于真实患者场景。
