# 预临床已知问题登记（未经临床审核）

> **性质**：本文登记的是**已确认的代码缺陷与未决问题**，不是待办愿望清单。
> **定级**：本系统为未经临床审核的原型。下列任何内容都未获得临床签署。
> **关联**：[交接文档](HANDOVER_TRIAGE_SAFETY_2026-10-02.md) 提供规则表、缺口清单与执行计划；本文只维护缺陷本体。
> **维护规则**：修复任一项时，将该条标记为 `已修复` 并注明 commit，**不要删除、不要重编号**——编号是稳定标识符，删除会打断本文与交接文档的交叉引用。

---

## 零、已修复

| 编号 | 摘要 | 修复 commit |
| --- | --- | --- |
| D1 | 合规拒答被记成普通追问引导，审计链断裂 | `3f028aa` |
| D4 | `待补充信息` 同时挂可预约医生，会话状态与 `riskLevel` 不一致 | `34c6f5f` |
| D2 | 面部肿胀零覆盖，且检索未命中会丢弃 URGENT 安全信号 | 本次提交 |
| D5 | `humanReviewRecommended` 为死字段，URGENT 仍可预约并扣号 | 本次提交 |
| D8 | 跨度型规则对否定词失效，`舌头没有肿` 被误判急症 | `48e2b9b` |
| — | 工具链事实：本机存在 JDK 17；JDK 25 会产生 mock 假失败 | `a13864d` |

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

### D2｜两个测试对"脸肿"给出虚假信心（**已修复**）

- 位置：
  - `backend/src/test/java/com/aihospital/triage/RuleBasedTriageEngineTest.java:65`（测试名 `expandedEmergencySignalsAreUrgent`）
  - `backend/src/test/java/com/aihospital/TriageConversationTests.java:192`（测试名 `safetyWarningDoesNotWaitForFollowUp`）
- 成因：两处输入均含 `吞咽不了` / `喘不上气`，**仅**因此通过。`脸`、`肿` 不在任何规则表达式中。
- 证据：用户 live 实测"智齿发炎，我的脸都肿起来了"未触发任何安全信号、无红色警示、无评估。
- 后果：测试名暗示"面部肿胀已被覆盖"，会掩盖后续回归。
- 修复方向：改为**单独**断言"脸肿"；如需保留原测试，另加不依赖呼吸道短语的独立用例。
- **裁定（Q5）**：单独"脸肿"**不作 EMERGENCY**，作 `尽快就医` 信号；显著张口受限、口底肿胀、呼吸或吞咽困难等组合才升级急诊拦截。SDCEP 的急诊条件并非"任何脸肿"。
- 已实施：
  - 新增 `UR-FACE-SWELLING-001`（URGENT）：`脸|面|脸颊|面部|牙龈|智齿` … `肿`，含倒装写法；全部经 tempered 填充（`48e2b9b` 建立的 `siteSymptom` / `gap()`），故 `脸没有肿`、`面部没有肿胀`、`没有脸肿` 均不触发
  - 新增 `ER-FACE-SPREAD-001`（EMERGENCY）：面部肿胀 + **同子句内** `无法吞咽|吞咽不了|呼吸困难|喘不上气|喘不过气|说不出话|张口受限|张不开嘴`，正反语序各写一遍；另含 `口底肿|口底三角区`
  - `POLICY_VERSION` → `CN-ADULT-ONLINE-TRIAGE-2026.10-P2`
  - 回归（新增，均**单独断言脸肿**）：`facialSwellingAloneIsUrgentNotEmergency`、`facialSwellingWithAirwayOrMouthOpeningFeatureEscalates`、`facialSwellingNegationsAndAdversativesDoNotEscalate`、`TriageConversationTests.dentalFacialSwellingAloneIsFlaggedUrgentAndNotBookable`
- **实施中发现并修复的更严重缺陷（原 D2 未记录）**：检索未命中时安全信号被丢弃。`TriageConversationService` 两处会吞掉已判定信号：
  1. L77 `needsClarification` 在 URGENT 时仍返回追问，把"今天就该线下评估"推迟成"先回答几个问题"
  2. L92 未命中检索即置 `待补充信息` 并丢弃分诊版本；原先只对 EMERGENCY 放行，**URGENT 被降级隐藏**
  修复：新增 `TriageEngine.requiresReview`（默认实现基于 `assessSafety`，避免破坏既有 mock），两处改为只对未触发信号追问/降级。`grounded` 仍为 false，界面照旧提示推理无证据支撑。
- **已知残留缺口（依赖 D9，未修）**：`assess()` 先按 `，,。；;！!？?` 切子句再匹配，因此**逗号分隔**的组合（`脸肿，张口受限`）不触发 `ER-FACE-SPREAD-001`，只落到 `尽快就医`；同子句内各种语序已实测可升级。
  - 风险方向为**降级**而非反向保证：患者仍被告知尽快线下就医，且因 D5 不可预约
  - 根因是 D9 的子句切分。子句切分对否定作用域是必要的安全机制，**不应在规则层绕过**
- 未经临床审核。SDCEP Dental Abscess 与 NHS dental abscess 为相关出处，非逐条映射审核。

### D3｜`ER-PREGNANCY-001` 并非不可达｜**已复裁定：选 A 保留，本次不改规则与入口（但已发现逗号漏判缺口）**

- 位置：规则 `TriageSafetyPolicy.java:31`；入口校验 `TriageConversationTests.java:37`、`unsupportedPopulationCannotStartStandardTriage`（L52-57）
- 成因：建会话硬性要求 `notPregnantConfirmed: true`，系统在进入分诊前即排除孕产。
- 矛盾：排除孕产的同时携带孕期急诊规则，等于两条互斥策略并存。回答"确实怀孕"的患者在分诊前被拒；回答"未怀孕"的孕产患者才会触发该规则。
- 现实场景影响：患者会直接陈述"我怀孕了"，此时系统无孕周采集、无产科目录、无产科分诊逻辑。
- **原记载已作废**：早前记为"永不可达"，并据此判断删除无害。**该判断错误**，理由如下。
- **患者路径的拦截靠自我声明，不是系统强制。** `create()` 要求 `notPregnantConfirmed: true`，但该值来自前端一个复选框（`TriagePage.vue:111`「我已年满 18 岁、为本人提问，且不处于孕产期」）。勾选后，`send()` 中**不再复查**孕产状态（`TriageConversationService:77/86` 直接以 `combined` 调 `requiresImmediateCare` / `triage`）。因此：勾选后陈述"怀孕八周突然剧烈腹痛"的患者，`ER-PREGNANCY-001` **今天就会触发**并输出急诊指引。删除该规则会移除这条窄但真实的兜底。
- **工具路径完全绕过入口校验。** `HospitalToolExecutor.java:49` 的 `symptom_tag_search` 用任意 `query` 直接调 `safety.assess()`，不经 `create()`。该工具经 MCP `tools/call` 暴露（`McpProtocolController.java:55-62`），受 `ADMIN` 角色保护，但对管理员真实可达。
- 已实测：`assess("怀孕两个月剧烈腹痛")` → `EMERGENCY`，`signals=[ER-PREGNANCY-001]`。
- 裁定 Q2=A 中"继续排除孕产人群"与"明确声明不覆盖孕产"仍然成立；但其依据"不可达故可删"不成立，**删除动作已暂停**，需重新裁定。
- **已复裁定（GPT，撤回原"应删除"，选 A）：保留 `ER-PREGNANCY-001`，本次不改规则、不改入口。**
  - 保留该规则**不等于**系统已覆盖孕产人群，它只是自我声明失准时的一道有限兜底。
  - C（入口改造）留到 Stage 2，采用「断言识别＋分级转出」，**不**采用见孕产词就无条件转出：明确表示本人正在孕期或近期产后 → 停止普通分诊并提示转向线下；仅提及否定、既往经历、第三人或不确定 → 不算已确认，可询问确认，确认前不生成普通预约；若同时出现现有急症信号，**先执行急症提醒，不等再次确认**。
  - D（收紧/拆分规则）**暂不做**。
  - 若将来考虑删除，GPT 明确不接受以免责文字补偿该风险。
- **患者可见边界（本次已实施，三处一致）**：勾选处（`TriagePage.vue` 资格声明，附"以上为一次性自我声明，系统不会再次核实"）、会话内常驻提示（同文件 `.disclaimer` 区，无条件可见）、建会话被拒路径（`TriageConversationService.create()` 的 400 文案，经前端 `catch` 透传）。
  - 三处均**不得**表述为"系统已通过勾选确认/排除孕产风险"，只陈述边界与求助路径。
- **孕产引用更正**：孕产相关论证**不得**再引 NG253。NG253 范围限定 16 岁以上且**非孕产/近期非孕产**（`tools/knowledge-sync/sources.json` 的 `nice-ng253` 条目已独立记录该范围）。孕产脓毒症应引 **NG255**。
  - 现引用：[CDC 孕产危险信号](https://cdc.gov/hearher/maternal-warning-signs/index.html)、[NHS 孕期腹痛](https://www.nhs.uk/pregnancy/common-symptoms/stomach-pain/)；待补 NG255。
  - **NG255 尚未登记进 `tools/knowledge-sync/sources.json`**，需由该清单的 owner 补录。
- **已实测的新缺口（重要，本次发现）**：**逗号会让 `ER-PREGNANCY-001` 完全漏判**。`assess()` 先按 `[，,。；;！!？?]` 切子句，部位词与症状词被逗号隔开即分属不同子句，跨子句的跨度表达式无法匹配。

  | 输入 | acuity | 命中规则码 |
  | --- | --- | --- |
  | `我怀孕八周突然剧烈腹痛`（无逗号） | `EMERGENCY` | `ER-PREGNANCY-001` `UR-PAIN-001` |
  | `我怀孕八周，突然剧烈腹痛`（有逗号） | **`URGENT`** | 仅 `UR-PAIN-001` |
  | `怀孕，剧烈腹痛`（有逗号） | **`URGENT`** | 仅 `UR-PAIN-001` |
  | `我没有怀孕，昨天开始轻微腹痛` | `ROUTINE` | 无（否定不误报，正确） |

  - **GPT 指定的验收文案「我怀孕八周，突然剧烈腹痛」恰好含逗号**，因此该验收用例在当前代码下**不通过**。本次落地的正向回归改用无逗号措辞。
  - 失效方向为**降级而非漏放**：仍由 `UR-PAIN-001` 判 URGENT，仍要求尽快就医、仍不可预约、仍置人工复核，所以不会反向保证"没事"。但这确实削弱了 GPT 要求保留的那道兜底。
  - 回归 `commaSeparatedPregnancyRedFlagCurrentlyDowngradesToUrgent` **故意固定当前降级行为**：修复时该用例会失败并迫使阅读注释，而不是被静默改掉。修复必须经临床审核并升 `POLICY_VERSION`，**不得**在规则层单方面绕过切句逻辑（与 D2 的 `gap()` 同源，见下）。
- **本条 D3 之前完全没有端到端测试保护**：`TriageConversationTests` 中"孕"字仅出现在建会话 payload。任何人改动 `assess()` 的切句或 `removeNegatedRedFlags`，都不会有任何测试拦住孕产急诊退化。本次已补正负两条端到端回归，负向按 rule code 断言（不按 acuity 断言，否则其他安全规则会掩盖误报）。
- 未经临床审核。规则表达式本次未改，故 `POLICY_VERSION` 未升；任何收紧须附出处并升 `POLICY_VERSION`。

### D4｜已修复（`34c6f5f`）｜独立复核已完成：在任务书范围内，55/55 全绿

以下为修复前的缺陷记录；当前实现及验证见 [D4 修复记录](D4_DISPOSITION_CONSISTENCY_2026-10-02.md)。

- 位置：`RuleBasedTriageEngine.java:179-184`（挂医生）、`L199-202`（`confidence < 60` → `待补充信息`）
- 成因：
  1. 只要 `!emergency` 就查询并挂载医生，不看处置是否可预约；
  2. 会话状态按"是否存在评估"置为 `已完成分诊`，与 `riskLevel` 无关。
- 证据：用户 live 实测"流鼻涕 → 无发热鼻塞 → 我要挂号"得到 `department=全科医学科`、`riskLevel=待补充信息`、`confidence=55`、`grounded=true`、会话 `已完成分诊`、有可预约医生且预约成功。
- 放大因素：四处鼻部专用硬编码——`NASAL_SYMPTOM`(L25)、`bookingFallbackCandidates`(L234-237)、`hasAffirmedNasalSymptom`(L239-248)、`guidedFallback` 鼻部分支(L264-270)。
- 测试固化问题：`TriageConversationTests.java:308-328`（`explicitBookingForNasalSymptomsCreatesGroundedGeneralMedicineRecommendation`）**把该 bug 写成了期望值**。
- 修复方向：处置不可预约时不挂医生；会话状态与 `riskLevel` 对齐；删除鼻部硬编码；重写上述测试。

### D5｜已修复｜`humanReviewRecommended` 曾是死字段，URGENT 可预约并扣号

- 位置：计算于 `TriageSafetyPolicy.java:63`（作为第 4 个构造参数，即 `humanReviewRecommended`），断言于 `RuleBasedTriageEngineTest.java:89`
- 成因：`Models.SafetyAssessment`（`shared/model/Models.java:16-18`）第 4 个字段是 `humanReviewRecommended`，但**全库无任何生产代码读取**。
- 后果：URGENT（`UR-TRAUMA-001`/`UR-FEVER-001`/`UR-PAIN-001`）实际不触发任何人工接管。
- 复核命令：`git grep -n "humanReviewRecommended"`（应只命中定义、赋值与测试断言）
- **裁定（Q1=A）**：当前没有医院急诊号源与临床复核流程，`URGENT` 阻断普通模拟预约与扣号，只给及时线下就医指引。将来接入医院后再设计独立的紧急转诊流程。
- 修复：新增 `triage/domain/Disposition.java` 集中定义处置词汇与两条判定，消除 D4 那种"三处各自判断可否预约"的结构性成因。
  - `isBookable`：`紧急` / `尽快就医` / `待补充信息` 均不可预约；`普通` 与 `多科室参考` 保持可预约（Q1 只要求阻断 URGENT，未要求牵连多科室）
  - `sessionStatus`：`尽快就医` → `建议尽快就医`。**关键**：`已完成分诊` 现在只在处置本身可预约时出现，否则会话会再次出现 D4 那种"声称完成却不可预约"的矛盾
  - 接入点：`RuleBasedTriageEngine` 挂医生、`TriageConversationService.withCurrentAvailability` 检索号源、`BookingApplicationService.book` 预约闸门，三处改为调用同一判定
  - 前端：`AssessmentCard.vue` 预约按钮排除 `尽快就医`；`TriagePage.vue` 对 `建议尽快就医` 会话显示线下就医与急诊升级提示
- 回归：`RuleBasedTriageEngineTest.urgentIsNotBookableButRoutineAndMultiDepartmentRemainBookable`、`TriageConversationTests.urgentDispositionBlocksBookingAndReportsItsOwnSessionStatus`（URGENT → `尽快就医` / `建议尽快就医` / 无医生 / 预约 `409`）
- 改写：`clearFirstTurnSymptomsRouteDirectlyAndMultipleSitesHaveMultipleDoctors` 原用"骨折会话 + 第 2 轮痛经"验证多科室有医生；该会话因 `combined` 仍含骨折而判 URGENT，新语义下正确地不附医生。已拆为独立用例，**不把 D9 的跨轮粘滞写成期望值**。

### D6｜已修复｜测试套件与 demo 模式解耦

- 位置：`TriageConversationTests.java:297` 断言 `provenance.modelStatus == "DEMO"`
- 后果：设 `AI_MODE=openai-compatible` 会使该套件失败。
- 说明：这是有意的严格断言（demo 模式不调外部模型），但代价是 live 行为无自动化覆盖。
- 修复方向：若要覆盖 live 路径，需分离"模式无关断言"与"模式相关断言"，或引入显式 profile。**非紧急。**
- 全仓核查后确认：**只有一处**真正钉死 demo 语义（`TriageConversationTests.java:384` 断言 `modelStatus == "DEMO"`）。其余 `DEMO` 均为注入桩构造参数，或本就该是 `SAFETY_RULE` / `POLICY_REFUSAL` 的路径，不受影响。
- 已实施：拆成模式无关契约 + 模式相关标识。
  - 模式无关（无条件断言）：回复非空、不含未检索话术、`knowledgeHits > 0`、无分诊版本、`modelStatus != "DEMO_UNGROUNDED"`（即由已配置的叙述路径产出，而非未命中回退）
  - 模式相关（仅 demo 下断言）：`modelStatus == "DEMO"`，由 `demoProfileActive()` 读 `Environment` 的 `ai.mode` 决定
- **已用证伪法验证解耦真实生效**，非仅改写文本：把条件内断言故意改成 `DELIBERATELY_WRONG_PROBE` 后，默认 demo 下该用例**失败**、加 `-Dai.mode=openai-compatible` 下**通过**（分支确被跳过）。随后恢复断言。
- 回归：`-Dai.mode=openai-compatible` 下全量 61/61 绿。**注意**：无 api-key 时叙述层会静默回退，故此项只证明断言已解耦，**不等于 live 链路行为已验证**。
- 仍未覆盖：live 链路的**行为**验证需要真实凭据，属人工验证范围，不在 CI 内。

### D7｜`UR-FEVER-001` 门槛与 NICE 指南冲突（**裁定 Q6=b：保留门槛并登记缺口**）

- 位置：`TriageSafetyPolicy.java:33`，表达式 `持续高热|高烧不退|体温.{0,3}(39\|40)`
- 冲突：NICE `NG253` §1.1 指出脓毒症 *"may not have a high temperature"*。现有规则把 ≥39℃ 当作门槛，结构上与该指南相反。
- 附加缺口：未覆盖低体温、寒战、皮肤冰冷。老年感染者低体温常见。
- 修复方向：补低体温/寒战表达，去掉"必须 ≥39℃"这一门槛。
- 约束：无临床团队无法仲裁阈值取舍。修改时必须附出处 + `未经临床审核` 标记，并升 `POLICY_VERSION`。
- **裁定（Q6=b）**：保留现有 ≥39℃ 门槛，**不自行改写**脓毒症规则；规则调整须经临床审核。同时必须避免界面或文档给出**反向保证**。
- 已实施的反向保证防线：`RuleBasedTriageEngine.FEVER_CAVEAT`。当文本提到 `发热|低热|发烧|体温|寒战` 且未命中 `UR-FEVER-001` 时，在 `safetyTip` 追加说明：体温未达急诊阈值**不代表可以排除严重感染**，并指出疑似脓毒症者可能并不发热、低体温/反应变差/意识改变也需整体评估（依据 NICE NG253，标注未经临床审核）。
  - 该文案只声明"不能排除"，**不新增诊断结论**，因此不升 `POLICY_VERSION`（规则表达式未变）。
  - 回归：`subThresholdFeverNeverReadsAsRulingOutSeriousInfection`，覆盖三条：38℃ 必含该说明、无发热话题不含、39℃ 已升 URGENT 不重复该说明。
- 门槛本身仍是**未修缺口**：低体温、寒战、皮肤冰冷仍未覆盖，等待临床审核后统一处理。

### D8｜跨度型规则对否定词失效，现网已有急症误报（**已修复**）

- 状态：**已修复并提交**。识别标记为 `POLICY_VERSION = "CN-ADULT-ONLINE-TRIAGE-2026.10-P1"`。
- 位置：`TriageSafetyPolicy.java`（`ER-AIRWAY-001` 等 5 条跨度规则、`isAsserted`）
- 成因：`isAsserted` 只检查匹配起点**之前** 14 字。任何写成 `A.{0,N}B` 的规则，若否定词落在 A 与 B **之间**，该否定词位于匹配区间内部，否定检查永远看不到它。
- 修复前的实测证据（jshell）：

  | 规则 | 输入 | 匹配起点 | 否定检查所见前缀 | 修复前结果 |
  | --- | --- | --- | --- | --- |
  | `ER-AIRWAY-001` | `舌头没有肿` | 0 | 空 | **asserted=true → 误判急症** |
  | `ER-AIRWAY-001` | `舌头肿了` | 0 | 空 | asserted=true（正确） |
  | `ER-AIRWAY-001` | `没有舌头肿` | 2 | `没有` | asserted=false（正确） |
  | `ER-CIRCULATION-001` | `没有胸痛` | 2 | `没有` | asserted=false（正确） |
  | `ER-CIRCULATION-001` | `胸痛没有缓解` | 0 | 空 | asserted=true（**正确**：胸痛未缓解确属急症） |

- **采用方案：tempered 填充（未采用有界后行断言）**。新增私有helper `siteSymptom(siteWords, maxGap, symptom)`，把跨度型表达式统一生成为
  `(?s)(?:部位)(?:(?!否定词)[^，,。；;！!？?]){0,N}(?:症状)`，即填充字符**不得跨过否定词**。
  - 覆盖 5 条规则：`ER-AIRWAY-001`、`ER-PREGNANCY-001`、`UR-FEVER-001`、`FOOD_REACTION`、`GENERALIZED_RASH`（后两条为配对输入）。
  - 否定词集合 `NEGATION_TOKENS` 含单词 `不`。
- **为何不用有界后行断言**：原建议的 `(?<=...)` 会把匹配起点从部位词移到症状词，从而**制造新的误报**。既有事故语料 `但没有全身红疹` 本应否定；后行断言下起点落在 `红疹`，前缀变成 `但没有全身`，`isAsserted` 判定为 asserted=true，配对后输出 `ER-ALLERGY-001` 急症。**这正是本次要修的同一类缺陷，方向相反，否决。**
- **为何不在匹配区间内搜否定词**：`NEGATION` 含单词 `无`，而 `无法吞咽`（`ER-AIRWAY-001`）、`单侧肢体无力`（`ER-NEURO-001`）本身含 `无`。朴素修法会把这两条急症规则整体失效。
  - tempered 填充不存在该副作用：防护只作用于**填充字符**，症状词与独立分支不经防护。`无法吞咽`、`吞咽不了` 是不带填充的独立分支，含 `无` 不受影响。
- 修复后实测（全绿）：

  | 输入 | 结果 |
  | --- | --- |
  | `舌头没有肿` / `咽喉没有肿` / `舌头不肿了` / `舌头不是肿的` / `舌头不水肿` | 不触发（原误报已消除） |
  | `全身没有红疹` / `全身没有红点` | 不触发 |
  | `体温没有39度` / `怀孕没有剧烈腹痛` / `吃了海鲜没有起疹` | 不触发 |
  | `舌头有点肿` / `舌头肿了` / `舌头水肿` / `咽喉稍微肿了一点` | `ER-AIRWAY-001` 正确触发 |
  | `无法吞咽` / `吞咽不了` / `单侧肢体无力` | 正确触发（未被 `无`/`不` 误伤） |
  | `但没有全身红疹` / `吃了海鲜但没有全身红疹` | 不触发（**原有过敏配对回归未破**） |
  | `我食物过敏了，现在全身好多红肿` | `ER-ALLERGY-001` 正确触发 |
  | `体温39度` | `UR-FEVER-001`（URGENT，非 EMERGENCY） |

- 回归测试：`RuleBasedTriageEngineTest.negationInsideSiteQualifiedSpanIsNotAsserted`、`temperedFillerKeepsAffirmedSiteQualifiedSymptomsAndWuTerms`。
- 行为变化（有意为之，保守方向）：`removeNegatedRedFlags` 对 `舌头没有肿` 这类"规则已不匹配"的分句不再删除，改为保留原文。理由：安全上宁可保留患者原话，也不得因规则不匹配而丢弃文本。
- **未覆盖的相邻缺口（非本次范围）**：`FOOD_REACTION` 的症状词要求 `起疹` / `红疹` **紧邻**，`吃了海鲜全身起了很多红点` 这类自然口语（`起了很多红点`）不匹配，故配对不触发。`起疹` 形态与 `起了很多红点` 的距离问题需与 D2 的 `ER-INFECTION-SPREAD-001` 一并设计，勿只放宽填充长度。
- D2 解禁：可在本条之后处理。

### D9｜历史消息拼接导致意图与结论跨轮粘滞

- 位置：`backend/src/main/java/com/aihospital/triage/application/TriageConversationService.java:73-75`
  ```java
  List<String> patientTexts = current.messages().stream().filter(m -> "USER".equals(m.role()))
          .map(Message::content).toList();
  String combined = patientTexts.stream().collect(Collectors.joining("。"));
  ```
  随后 `requiresImmediateCare(combined)`、`needsClarification(combined)`、`triage(..., combined, ...)` 全部以 `combined` 为输入。
- 成因：安全判定、意图拒答、候选科室、处置与检索 query 全部基于**全会话**拼接文本，而非当前轮。
- 可证明的后果：
  1. **拒答粘滞**：第 1 轮问"开点处方"被拒后，第 2 轮即使只说"我头痛三天"，`combined` 仍含拒答意图，`needsClarification` 继续返回 `true`，患者在同一会话内**永远无法进入分诊**，也拿不到科室。
  2. **科室粘滞**：第 1 轮的部位关键词持续参与 `candidatesFor`，患者换话题后旧症状仍会产出候选科室。
  3. **急症粘滞**：历史急症信号使 `requiresImmediateCare(combined)` 长期为真。
  4. **否定语境污染**：`removeNegatedRedFlags(combined)` 作用在拼接文本上，跨轮的否定/历史判断互相干扰。
- 实证状态：**未实测确认**。用户曾在同一会话混合"化验单/血压/头痛"并最终得到神经内科，但该结果与粘滞行为一致，不足以单独证明。建议补一个两轮测试：先拒答、再报普通症状，断言第 2 轮能正常分诊。
- 修复方向：区分"当前轮意图"与"会话事实"。合规拒答应只看当前轮；急症与否定处理应保留跨轮（避免患者第 2 轮否认时抹掉第 1 轮的真实红旗）。具体切分方式需与 §三 Q1/Q2 一并裁定，**不要单方面改动**。

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
| 可用 JDK | 基准 `E:\JDK17\jdk-17.0.1`（JDK 17）。需先设 `$env:JAVA_HOME`。另有 `D:\Elasticsearch\elasticsearch-9.3.3\jdk`（JDK 25） |
| 默认 `java` | 1.8.0_221，`mvn test` 报 `class version 61` |
| `D:\FinallShell\finalshell\jre` | 缺 `com.sun.net.httpserver`，`QdrantSemanticIndexTest` 抛 `NoClassDefFoundError` |
| JDK 25 能否跑测试 | **不能**。本项目 Mockito 依赖的 Byte Buddy 仅支持到 Java 23；JDK 25 下 mock **具体类**（如 `SimulationBookingService`、`DoctorCatalogService`）报 `Java 25 (69) is not supported by the current version of Byte Buddy`。仅 mock 接口的用例碰巧能过，易误判为可用 |
| 本机是否存在 JDK 17 | **存在**，`E:\JDK17\jdk-17.0.1`（17.0.1+12）。此前"否、已修正为 JDK 25"的结论**错误**，源于未实际探测全部 JDK 路径 |
| 测试基线 | **仓库已提交 53 项 / 9 个测试类全绿**（无任何 `AI_*` 环境变量）。工作区含在途 D4 用例时为 55 项 / 10 个类 |
| 计数陷阱 | `target/surefire-reports/` 残留已删除测试类的旧报告（`LocalRetrieveProbeTest`、`SourceEncodingProbeTest`），直接汇总会多算 2 项。统计时按文件修改时间过滤 |
| PowerShell 中文 | 请求体必须 `[System.Text.Encoding]::UTF8.GetBytes($json)`，`ContentType` 带 `charset=utf-8`。否则正文变 `????`，**所有安全规则全部漏判** |
| 管理端鉴权 | 必须 `Authorization: Bearer <token>`。漏 `Bearer ` 前缀会被误判为 `JwtService` 缺陷 |
| 数据核查 | 勿用 H2 文件 mtime 或应用日志推断有无写入：只有登录请求被记录。用管理端 API 查询 |

---

## 五、已作废的结论（勿据以决策）

| 旧结论 | 出处 | 状态 |
| --- | --- | --- |
| 百炼 chat 额度耗尽（`AllocationQuota.FreeTierOnly`） | `HANDOVER_2026-10-01.md:23` | **已作废**。`glm-5.3` live 调用实测成功 |
| 测试基线 44 项 | `HANDOVER_2026-10-01.md:50` | **已作废**，现为 53 项 |
| `stopRoutineFlow` 含 URGENT | 撰写者口头表述 | **错误**。仅含 EMERGENCY；`humanReviewRecommended` 才含 URGENT |
| 11 条规则全为 EMERGENCY | 撰写者口头表述 | **错误**。8 EMERGENCY + 3 URGENT，另有 1 条派生 = 共 12 个规则码 |
| 规则+测试可得出召回率 | 撰写者口头承诺 | **错误**。规则与测试同源，构造上必然 100%，无信息量。禁止命名为召回率/敏感度/安全指标 |

---

**本文档不构成临床认可。** 所列缺陷的修复方案与新增规则均需具备资质的临床人员审核后，方可用于真实患者场景。
