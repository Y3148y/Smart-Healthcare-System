# 交接文档：预问诊安全层加固（2026-10-02）

> **本文读者**：接手的 GPT 会话。
> **写作者**：opencode（big-pickle）。
> **状态**：代码勘察、红旗缺口分析、缺陷定位、Stage 0 已完成。**Stage 1 起需逐项推进，每项独立提交。**
> **前置裁定**：`§6` 三个问题**尚无结论**，接手方在裁定前不得动对应代码（阻塞 `D5` 与 Stage 2）。
> **缺陷本体**：见 [KNOWN_ISSUES_PRECLINICAL.md](KNOWN_ISSUES_PRECLINICAL.md)。本文只维护现状、缺口与计划。
> **上线定级**：未经临床审核的原型。详见 `§10`。

---

## 0. 硬约束（违反即任务失败）

1. **无临床团队。** 任何新增临床内容不得声称已审核。每条新规则必须带出处原文 + `未经临床审核` 标记，并随 `POLICY_VERSION` 记录。
2. **不得放宽现有规则。** 缺口靠新增，不靠删改已生效的安全规则。分诊系统应偏 over-triage。
3. **密钥不入库。** 会话中已暴露的百炼 key 需用户轮换；代码、文档、日志、测试数据均不得含真实 key。
4. **不要重构 `TriageSafetyPolicy` 的否定/历史机制。** `isAsserted` 已被 `RuleBasedTriageEngineTest.java:74-91` 的安全矩阵锁定，改动必须同步重写该矩阵。
5. **范围限于成人非孕产。** 不引入儿科。孕产见 `§4 D3` 与 `§6 Q2`。
6. **基准 JDK 为 `E:\JDK17\jdk-17.0.1`（JDK 17）。** 默认 JDK 8 报 `class version 61`；`D:\FinallShell\finalshell\jre` 缺 `com.sun.net.httpserver`，会在 `QdrantSemanticIndexTest` 抛 `NoClassDefFoundError`。**不要用 JDK 25 跑测试**（见第 4 项与 `AGENTS.md` 第 4 节）。
7. **沿用 `AGENTS.md` 红线**：单写手/复核分离、每个验收任务一个 commit、文档是事实源、不得夸大未验证结论。

---

## 1. 目标

把当前"演示型自由文本分诊"改造为面向患者的**成人线上预问诊原型**的确定性安全层，使其：

- 高危信号不依赖模型散文判定，模型永远无法覆盖安全结论
- 处置（disposition）自洽：`riskLevel`、会话状态、是否挂医生三者一致
- 规则覆盖可审计、可追溯到公开指南
- 每次变更可独立提交、可回归

**明确不做**：诊断、处方、治疗建议（依据 `国卫办医发〔2022〕2号` 第二十一条，代码注释已引用）。

---

## 2. 已核实的现状（读码所得，非推测）

### 2.1 安全规则表

`backend/src/main/java/com/aihospital/triage/domain/TriageSafetyPolicy.java` 共 **11 条静态规则 + 1 条派生组合 = 12 个规则码**。

| 规则码 | 类别 | acuity | 表达式要点（行号） |
| --- | --- | --- | --- |
| `ER-AIRWAY-001` | 气道 | EMERGENCY | 舌/咽喉/喉头肿、无法吞咽、窒息、说不出话（L24） |
| `ER-BREATHING-001` | 呼吸 | EMERGENCY | 呼吸困难、不能平卧、口唇/嘴唇发紫、咯血（L25） |
| `ER-CIRCULATION-001` | 循环 | EMERGENCY | 胸痛全部变体，含裸 `胸痛`（L26） |
| `ER-NEURO-001` | 神经 | EMERGENCY | 意识不清、昏迷、**晕厥**、口角歪斜、单侧无力、说话不清、突发剧烈头痛、抽搐（L27） |
| `ER-BLEEDING-001` | 出血 | EMERGENCY | 伤口大量出血、呕血、大量咯血、便血不止、黑便伴头晕（L28） |
| `ER-TRAUMA-001` | 严重创伤 | EMERGENCY | 骨头外露、开放性骨折、肢体断裂（L29） |
| `ER-POISON-001` | 中毒与自伤 | EMERGENCY | 自杀、自残、不想活、服药过量、中毒、误服农药（L30） |
| `ER-PREGNANCY-001` | 孕产 | EMERGENCY | 怀孕+大量出血/剧烈腹痛、产后大出血（L31）— **见 `§4 D3`** |
| `UR-TRAUMA-001` | 创伤 | **URGENT** | 骨折、摔断、明显变形、不能活动（L32） |
| `UR-FEVER-001` | 感染 | **URGENT** | 持续高热、高烧不退、`体温.{0,3}(39\|40)`（L33）— **见 `§4 D7`** |
| `UR-PAIN-001` | 疼痛 | **URGENT** | 剧烈腹痛、腹痛难忍、疼痛难忍（L34） |
| `ER-ALLERGY-001` | 疑似严重过敏 | EMERGENCY | 派生：`FOOD_REACTION` ∧ `GENERALIZED_RASH`（L53-57） |

辅助模式：`NEGATION`(L18)、`FOOD_REACTION`(L19)、`GENERALIZED_RASH`(L20)、`HISTORICAL`(L21)、`CURRENT_RESET`(L22)。

`POLICY_VERSION = "CN-ADULT-ONLINE-TRIAGE-2026.10-P1"`（L17；D8 修复时由 2026.09-P0 升级）。

`isAsserted`（L106-121）以匹配点**前 14 字**为前缀窗口做否定/历史判定。这是有意的窄窗口，勿扩。

### 2.2 `SafetyAssessment` 字段顺序（易错点）

```java
// backend/src/main/java/com/aihospital/shared/model/Models.java:16-18
public record SafetyAssessment(String policyVersion, String acuity, boolean stopRoutineFlow,
                               boolean humanReviewRecommended, List<SafetySignal> signals,
                               List<String> actions) {}
```

```java
// TriageSafetyPolicy.java:63
return new SafetyAssessment(POLICY_VERSION, acuity, emergency, emergency || urgent, ...);
```

因此：

- `stopRoutineFlow` = **仅 EMERGENCY**
- `humanReviewRecommended` = EMERGENCY **或** URGENT

由 `RuleBasedTriageEngineTest.java:89-90` 反证：

```java
assertTrue(safety.assess("手摔断了").humanReviewRecommended());
assertFalse(safety.assess("手摔断了").stopRoutineFlow());
```

### 2.3 URGENT 的实际行为

`RuleBasedTriageEngine.java:141`：`boolean emergency = safetyAssessment.stopRoutineFlow();`

故 **URGENT 不阻断路由**：正常算候选科室、正常选医生、正常挂可预约号源，仅在 `L201` 把 `riskLevel` 写成 `"尽快就医"`。

例：`"我手摔断了"` → URGENT → 骨科 + 可预约医生 + `尽快就医`（`TriageConversationTests.java:152-158` 锁定）。

### 2.4 候选科室生成（全部硬编码，5 个）

`RuleBasedTriageEngine.java:213-227`：神经内科、消化内科、呼吸内科、骨科、妇科。均为 `text.matches("(?s).*(关键词).*")` 形式。

无候选且有挂号意图时走 `bookingFallbackCandidates`（L234-237）——**仅支持鼻部症状**，见 `§4 D4`。

### 2.5 意图拒答

```java
// RuleBasedTriageEngine.java:49-53
private static final Pattern DIAGNOSIS_OR_PRESCRIPTION_INTENT = Pattern.compile(
        "(开药|开处方|处方|开方|买药|确诊|诊断一下|能治吗|怎么治疗|用什么药|是不是.{0,10}(病|炎|感染)|(?:胃|肠|肺|肝|肾|胆|胰|心|脑|血|甲|乳)[^，。？！,.?!]{0,6}(病|炎|感染|癌|结石|息肉))");
private static final String PRESCRIPTION_REFUSAL = "本演示系统不提供诊断、处方或药物建议，亦不能自动生成治疗方案。"
        + "你希望判断就医方向或生成预约，请补充最主要的不适、持续时间和变化；"
        + "如果需要人工协助，可在会话页选择“需要人工导诊？提交申请”（演示系统仅记录申请，不保证实时响应）。";
```

- `requiresHumanHandover`（L83）为测试而暴露的 public 方法。`TriageConversationTests.java:275` 用 `new RuleBasedTriageEngine(null,null,null,null,null,null,null)`（7 参构造器）实例化。**改构造器签名会破坏该测试。**
- 该正则含 `(?:胃|肠|肺|肝|肾|胆|胰|心|脑|血|甲|乳)[^，。？！,.?!]{0,6}(病|炎|感染|癌|结石|息肉)` 这一组织+病变模式，用于覆盖 `"帮我看看是不是胃癌"`。

### 2.6 运行时配置

```yaml
# backend/src/main/resources/application.yml:28-33
ai.mode: ${AI_MODE:demo}          # demo | openai-compatible（无 "live"）
ai.base-url: ${AI_BASE_URL:}
ai.api-key: ${AI_API_KEY:}
ai.model: ${AI_MODEL:}
ai.timeout-seconds: ${AI_TIMEOUT_SECONDS:35}
```

---

## 3. 用户 live 实测记录（百炼 `glm-5.3`）

| 输入 | 实际结果 | 判定 |
| --- | --- | --- |
| 胃疼想开处方 | `modelStatus=POLICY_REFUSAL`，`localToolCalls=0`，`assessments=0`，回复逐字等于 `PRESCRIPTION_REFUSAL` | 正确 |
| `帮我看看化验单` | 未被拒，正常分诊 | 正确 |
| `头痛三天，想挂号` | LIVE → 神经内科 | 正确 |
| **"智齿发炎，我的脸都肿起来了"** | **无安全信号、无红色警示、无评估**；仅模型散文说"今天就医" | **安全缺陷** |
| **流鼻涕 → 无发热鼻塞 → 我要挂号** | `department=全科医学科`，`riskLevel=待补充信息`，`confidence=55`，`grounded=true`，会话 `已完成分诊`，**有可预约医生且预约成功** | **语义矛盾** |
| 一次 GLM 请求 | `FALLBACK_UNGROUNDED`，`elapsedMs=35279` | 超时 |
| 化验单/血压/头痛混一会话 | 疑似跨主题上下文污染 | **未实测确认** |

### 3.1 调试注意（两处曾导致误判）

- 管理端 API 必须 `Authorization: Bearer $token`。漏掉 `Bearer ` 前缀会被误判为 `JwtService` 鉴权 bug——实际不是。
- PowerShell 5.1 发中文请求体必须用 `[System.Text.Encoding]::UTF8.GetBytes($json)`，`ContentType` 带 `charset=utf-8`。否则正文变成 `????`，**所有安全规则全部漏判**。
- 不要用 H2 文件 mtime 或应用日志推断"是否有写入"：只有登录请求被记录。数据变更请用管理端 API 查询。

### 3.2 对既有文档的更正

`docs/HANDOVER_2026-10-01.md:23` 记载"百炼 chat 额度耗尽（`AllocationQuota.FreeTierOnly`）"。**该结论已过时**：`glm-5.3` live 调用已成功并通过端到端验证。该文档同行的"44 项测试"基线也已被 53 项取代。接手方若读到旧结论，请以本文为准，并在合适时机更新旧文档。

---

## 4. 已确认缺陷（读码发现，含写作者的三处自我更正）

### 更正 1：`stopRoutineFlow` 不含 URGENT
写作者此前口头表述为 `emergency || urgent`，**错误**。实际仅 EMERGENCY。依据见 `§2.2`。

### 更正 2：是 8 EMERGENCY + 3 URGENT，不是 11 条全 EMERGENCY
共 11 静态 + 1 派生 = 12 个规则码。

### 更正 3："给你一个召回率数字"是错误承诺
规则与测试由同一作者写，构造上必然 100%，无信息量。**禁止把它命名或呈现为召回率/敏感度/安全指标。** 语料集的真实价值只有三条：固定意图、防否定与口语变体回归、供未来临床人员复核的工件。

### D1｜✅ 已修复（`3f028aa`）｜曾不可达，合规审计链缺失

唯一生效路径是 `TriageConversationService:76` → `clarificationPrompt`（因 `needsClarification` 对拒答意图返回 `true`）；而该分支原先记为 `purpose="预问诊引导"`、`success=false`，使 `prescriptionRefusalResult` 中正确的 `合规拒答` 永不执行。已抽出 `recordComplianceRefusal(...)` 统一审计入口。`prescriptionRefusalResult` 保留作纵深防御（服务层不可达是期望属性）。回归见 `complianceRefusalIsAuditedAsComplianceAndNeverAsOrdinaryGuidance`。

### D2｜两个测试对"脸肿"给出虚假信心

```java
// RuleBasedTriageEngineTest.java:65  —— 测试名 expandedEmergencySignalsAreUrgent
assertTrue(safety.requiresImmediateCare("脸肿而且吞咽不了"));

// TriageConversationTests.java:192  —— 测试名 safetyWarningDoesNotWaitForFollowUp
// 输入："我突然脸肿、吞咽不了，感觉喘不上气"
```

两者**仅**因 `吞咽不了` / `喘不上气` 通过。删掉这些短语即失败。`脸`、`肿` 本身不在任何规则中——这正是 `§3` 中用户实测失败的原因。测试名会掩盖后续回归。

### D3｜`ER-PREGNANCY-001` 永不可达

入口硬性要求 `notPregnantConfirmed: true`（`TriageConversationTests.java:37`，另见 `unsupportedPopulationCannotStartStandardTriage` L52-57）。系统在排除孕产的同时又携带孕期急诊规则。

真实患者会直接说"我怀孕了"，届时系统无孕周采集、无产科目录、无产科分诊逻辑。**处理方式待裁定（`§6 Q2`）。**

### D4｜预约矛盾是结构性的

- `RuleBasedTriageEngine.java:179-184`：在 `!emergency` 时一律挂医生
- `L199-202`：`confidence < 60` 时给 `riskLevel = "待补充信息"`
- 会话状态按"有无评估"置为 `已完成分诊`，与 `riskLevel` 无关

**后果**：出现 `待补充信息` + 非空医生 + `已完成分诊` + 可预约，即 `§3` 第 5 行。

放大因素是四处鼻部专用硬编码：`NASAL_SYMPTOM`(L25)、`bookingFallbackCandidates`(L234-237)、`hasAffirmedNasalSymptom`(L239-248)、`guidedFallback` 鼻部分支(L264-270)。

`TriageConversationTests.java:308-328`（`explicitBookingForNasalSymptomsCreatesGroundedGeneralMedicineRecommendation`）**把该 bug 写成了期望值**，改动时必须一并重写。

### D5｜`humanReviewRecommended` 是死字段

该字段被计算并在 `RuleBasedTriageEngineTest.java:89` 断言，但**无任何生产代码读取**。URGENT 实际不触发任何人工接管。复核命令：`grep -rn "stopRoutineFlow\|humanReviewRecommended"`（全库 11 处命中，除上述外无生产读取）。

### D6｜测试套件绑定 demo 模式

`TriageConversationTests.java:297` 断言 `modelStatus == "DEMO"`。设 `AI_MODE=openai-compatible` 会使该套件失败，**live 路径无法在 CI 验证**。

### D7｜`UR-FEVER-001` 门槛与指南冲突

要求 `持续高热|高烧不退|体温39+`。NICE `NG253` §1.1 明确脓毒症 *"may not have a high temperature"*。现有规则在结构上与该指南相反。无临床团队无法仲裁，但至少应在文档中登记为已知缺口。

### D8｜跨度型规则对否定词失效，**现网已有急症误报**（**已修复**）

`isAsserted` 只检查匹配起点**之前** 14 字。凡写成 `A.{0,N}B` 的规则，若否定词落在 A 与 B **之间**，它位于匹配区间内部，否定检查永远看不到。

修复前的实测证据：

| 规则 | 输入 | 匹配起点 | 否定所见前缀 | 修复前结果 |
| --- | --- | --- | --- | --- |
| `ER-AIRWAY-001` | `舌头没有肿` | 0 | 空 | **asserted=true → 误判急症** |
| `ER-AIRWAY-001` | `没有舌头肿` | 2 | `没有` | asserted=false（正确） |
| `ER-CIRCULATION-001` | `没有胸痛` | 2 | `没有` | asserted=false（正确） |
| `ER-CIRCULATION-001` | `胸痛没有缓解` | 0 | 空 | asserted=true（正确） |

- **现网缺陷已消除**：`舌头没有肿` / `咽喉没有肿` 这类**明确否认肿胀**的描述不再被判 `EMERGENCY`、不再输出 120 指引。
- **采用 tempered 填充**：新增 `siteSymptom(siteWords, maxGap, symptom)`，跨度表达式统一为
  `(?s)(?:部位)(?:(?!否定词)[^，,。；;！!？?]){0,N}(?:症状)`，填充字符不得跨过否定词。覆盖 `ER-AIRWAY-001`、`ER-PREGNANCY-001`、`UR-FEVER-001`、`FOOD_REACTION`、`GENERALIZED_RASH`。
- **`POLICY_VERSION` 升为 `CN-ADULT-ONLINE-TRIAGE-2026.10-P1`**。
- **未采用有界后行断言**：`(?<=...)` 会把匹配起点从部位词移到症状词，令既有语料 `但没有全身红疹` 的前缀变成 `但没有全身`，被判 asserted=true 并输出 `ER-ALLERGY-001`——即制造同类的新误报，方向相反。
- **禁止的修法仍然成立**：不可在匹配区间内搜否定词。`NEGATION` 含单词 `无`，而 `无法吞咽`（`ER-AIRWAY-001`）、`单侧肢体无力`（`ER-NEURO-001`）本身含 `无`；朴素修法会让这两条急症规则整体失效。tempered 填充只防护**填充字符**，两个独立分支不经防护，故实测仍正常触发。
- 回归：`RuleBasedTriageEngineTest.negationInsideSiteQualifiedSpanIsNotAsserted`、`temperedFillerKeepsAffirmedSiteQualifiedSymptomsAndWuTerms`；既有 `但没有全身红疹` 过敏配对用例未破。
- **相邻缺口（非本次范围）**：`FOOD_REACTION` 要求 `起疹` / `红疹` 紧邻，`吃了海鲜全身起了很多红点` 不匹配，配对不触发。需与 D2 的 `ER-INFECTION-SPREAD-001` 一并设计，勿只放宽填充长度。
- **D2 已解禁**，可在 D8 之后处理。详细修法与实测矩阵见 [KNOWN_ISSUES_PRECLINICAL.md](KNOWN_ISSUES_PRECLINICAL.md) D8 条目。

---

## 5. 红旗覆盖缺口（对照公开指南）

已覆盖 ✅ / 部分 ⚠️ / 缺失 ❌。**每条新增规则都必须附出处原文 + `未经临床审核` 标记。**

### 5.1 感染扩散与气道受累（❌ 全缺，用户实测踩到）

依据：SDCEP `Dental Abscess`；UHSussex 成人脓毒症安全网

| 表现 | 现状 |
| --- | --- |
| 面部 / 口底 / 颈部肿胀 | ❌ |
| 张口受限（trismus） | ❌（`ER-AIRWAY-001` 仅覆盖舌/咽喉/喉头） |
| 眼睑闭合困难 | ❌ |
| 感染扩散 / 流脓 / 蜂窝织炎 | ❌ |

建议 `ER-INFECTION-SPREAD-001`，EMERGENCY。

### 5.2 脓毒症症状集（❌ 整类缺）

依据：NICE `NG253` §1.1；`nhs.uk/conditions/sepsis`

| 表现 | 现状 |
| --- | --- |
| 新发意识改变 / 谵妄 / 极度嗜睡 | ⚠️ `ER-NEURO-001` 部分 |
| 说话含糊 | ✅ |
| 极端寒战 / 肌肉痛 | ❌ |
| 皮肤苍白、花斑、发绀（超出"口唇发紫"） | ❌ |
| 压之不褪色的皮疹 | ❌ |
| 18 小时未排尿 / 尿量极少 | ❌ |
| "感觉要死了" / 极度不适 | ❌ |
| 伤口红肿渗液 | ❌ |
| 静息心率 >120 | ❌ 无生命体征采集通道 |

建议 `ER-SEPSIS-001`，EMERGENCY。**注意**：无生命体征意味着只能靠症状描述，召回率必然受限，须在文档中声明。

### 5.3 体温异常（⚠️ 需改）

补：低体温、寒战、皮肤冰冷。老年感染者低体温常见。不应把 ≥39℃ 当作门槛（见 `D7`）。

### 5.4 P1 常见急症（❌ 缺）

| 类别 | 缺失红旗 |
| --- | --- |
| 糖尿病急症 | 血糖极高/极低、酮症、恶心呕吐+口渴多尿 |
| 肾梗阻 | 无尿、腰痛伴血尿 |
| 眼部急症 | 突发视力丧失、剧烈眼痛伴恶心 |
| 肢体缺血 | 单侧肢体发凉、苍白、麻木无力（无创伤） |
| 脱水 | 无法进水、持续呕吐、尿量极少 |

### 5.5 结构性缺口（非正则可解）

- **生命体征无采集通道**：心率、呼吸、尿量、血压全部不可用。
- **无年龄采集**：`CN-ADULT-ONLINE-TRIAGE` 仅靠前端 `adultConfirmed` 声明保证。
- **自由文本 vs 固定问句**：NICE / NHS 采用临床层级固定提问；本系统自由文本优先，表述组合无法穷举。

---

## 6. 待裁定问题（**接手方必须先取得裁定，不得自行决定**）

> 写作者曾自行给出建议，后被 owner 否决并要求交 GPT 裁定。以下三问**无结论**。

### Q1｜URGENT 是否应阻断在线预约？

背景：URGENT 现状给出可预约号源（`§2.3`）。`UR-PAIN-001`（剧烈腹痛）→ 消化内科普通号，而腹痛可能是 AAA / 异位妊娠 / 肠梗阻。另：入口已强制非孕产（`§4 D3`），故异位妊娠按现规约不可达。

- (a) 阻断预约 + 转人工（接通 `humanReviewRecommended`，属还原既有设计意图）
- (b) 维持可预约，仅强化文案
- (c) 按类别区分：可门诊处理的保留，感染/出血/剧烈疼痛类阻断

### Q2｜孕产矛盾（D3）如何处理？

- (a) 建孕产筛查问句 → 产科/急诊分支（需改会话状态机 + 前端）
- (b) 明确不支持孕产，删 `ER-PREGNANCY-001` + 明确拒答话术
- (c) 中途会话加确定性孕产断言门：不出科室/医生/预约，固定转出话术 + 人工入口

### Q3｜下一轮做到哪一层？

| Stage | 范围 | 是否改交互 |
| --- | --- | --- |
| 0 | 提交在手补丁 + 补全测试断言 + 写已知问题文档 | 否 |
| 1 | 纯 bug 修复（D1/D2/D4/D5） | 否 |
| 2 | P0 红旗规则 + 语料集 | 否 |
| 3 | 固定高危问句（4 轮） | **是** |
| 4 | 生命体征采集，或书面声明不做该判断 | **是** |

---

## 7. 执行计划

### 7.1 前置（需用户操作，不由接手方执行）

- 轮换百炼 key（已暴露于会话历史）
- 停止后端 PID 40152（监听 8080），日志 `C:\Users\y\AppData\Local\Temp\opencode\backend-live.log`
- 停止前端 PID 37392（5188）

### 7.2 工作树现状（写入后核对）

**工作树干净，无未提交修改。** 在手补丁已提交为 `ef8e58f`（Stage 0 完成）。

`ef8e58f` 实际内容（`93 insertions(+), 11 deletions(-)`）：

| 文件 | 变更 |
| --- | --- |
| `backend/src/main/java/com/aihospital/triage/infrastructure/demo/RuleBasedTriageEngine.java` | +54/-11 |
| `backend/src/test/java/com/aihospital/TriageConversationTests.java` | +50 |

测试侧：新增 `prescriptionRequestIsRefusedVerbatimAndOffersNoDepartmentOrBooking`、`diagnosisIntentPatternDoesNotSwallowOrdinaryTriageQuestions`；并把拒答测试从两处 `contains` 弱断言强化为逐字文案 + `modelStatus=POLICY_REFUSAL` + `localToolCalls=0` + 预约 409。

**D1 的代码修复尚未开始**：`ef8e58f` 走的是 `clarificationPrompt` 路径，因此 `prescriptionRefusalResult` 仍是死代码，`合规拒答` CallLog 仍未发出。Stage 1 继续。

最近提交（新→旧）：

```
ef8e58f fix: make diagnosis/prescription refusal deterministic and pin it verbatim
1ae623b docs: confirm 48 tests pass on a usable JDK and record toolchain caveats
6d99397 feat: refuse diagnosis/prescription intent and point to human review
6b7dd4e fix: block routine booking for suspected systemic food-allergic reaction
0cdfd4a fix: allow grounded general-medicine booking for nasal symptoms
6adde0b docs: record live Bailian glm-5.3 triage verification
```

`6d99397` 的拒答设计有缺陷（见 `D1`），已被 `ef8e58f` 取代，勿以其实现为基线。

### 7.3 Stage 0｜✅ 已完成（`ef8e58f` + 本次文档提交）

原计划四项均已落地：

1. ✅ `git diff` 逐行核对，确认无密钥
2. ✅ 拒答测试强化为逐字契约 + 状态 + 零工具调用 + 预约 409
3. ✅ 核对 `diagnosisIntentPatternDoesNotSwallowOrdinaryTriageQuestions` 期望
4. ✅ 新建 `docs/KNOWN_ISSUES_PRECLINICAL.md`（**缺陷本体登记表**）
5. ⚠️ 后被推翻：本条曾断言"本机无 JDK 17"并把 `AGENTS.md` 改为 JDK 25。2026-10-02 复核发现 `E:\JDK17\jdk-17.0.1` 确实存在，JDK 25 才是错误选择（Byte Buddy 仅支持到 Java 23）。已改回 JDK 17

回归：**53 项 / 9 个测试类全绿**。统计陷阱见 `KNOWN_ISSUES_PRECLINICAL.md` 第四节——`target/surefire-reports/` 残留两份已删除测试类的旧报告，直接汇总会多算 2 项得到 52。

### 7.4 Stage 1｜纯 bug 修复（不新增临床内容）

- **D1**：✅ 已完成（`3f028aa`）
- **D8**：✅ 已完成。跨度型规则改为 tempered 填充，否定词不再落入匹配区间内部；`POLICY_VERSION` → `2026.10-P1`；新增两条否定/肯定回归
- **D2**：✅ 已完成（Q5）。单脸肿 → `UR-FACE-SWELLING-001`（尽快就医）；同子句内叠加吞咽/呼吸/张口受限或口底肿 → `ER-FACE-SPREAD-001`（紧急）。`POLICY_VERSION` → `2026.10-P2`。顺带修复"检索未命中即丢弃 URGENT 安全信号"重写 `RuleBasedTriageEngineTest.java:64-72` 与 `TriageConversationTests.java:190-196`，**单独**断言「脸肿」，不依赖呼吸道短语。需与 Stage 2 的 `ER-INFECTION-SPREAD-001` 同批落地；同时处理 D8 条目登记的 `起了很多红点` 紧邻缺口
- **D4**：✅ 已完成（`34c6f5f`，协作者实现）。处置非 bookable 时不挂医生；会话状态与 `riskLevel` 对齐；删除四处鼻部硬编码；重写鼻部相关测试并新增 `DispositionConsistencyTest`。独立复核已完成：在任务书约定范围内，55/55 全绿，未触碰 `TriageSafetyPolicy.java`。
  - **复核附注（需 owner 确认，非缺陷）**：`0cdfd4a`「allow grounded general-medicine booking for nasal symptoms」被本次实质回退。鼻部症状 + 明确挂号意图现在得到 `待补充信息` + 无任何科室 + 预约 `409`。这与任务书一致（那项能力本身就是硬编码），但它是**能力移除**而非纯 bug 修复，且搭在"bug fix"提交里，建议由 owner 显式追认。
- **D5**：✅ 已完成（Q1=A）。URGENT 阻断预约与扣号；新增 `Disposition` 集中定义处置词汇；`尽快就医` 会话状态为 `建议尽快就医`；`已完成分诊` 仅在可预约时出现
- **D9**：`TriageConversationService:75` 把所有历史 USER 消息拼成 `combined`，第 1 轮的拒答意图会永久粘住后续轮次。属行为变更，需与 GPT 商定按轮次意图还是按会话意图
- 可选：`AI_TIMEOUT_SECONDS` 默认 35 → 20。代价是 fallback 率上升。**默认不动**，单独提 commit 交用户定
- 回归须全量通过（**当前基线 61/61 / 10 个测试类**，JDK 17）

### 7.5 Stage 2｜P0 规则 + 语料（依赖 §6 Q2）

- 新增 `ER-INFECTION-SPREAD-001`（EMERGENCY，源：SDCEP Dental Abscess）
- 新增 `ER-SEPSIS-001`（EMERGENCY，源：NICE NG253 §1.1 / NHS sepsis）
- 孕产处理按 `§6 Q2` 裁定执行
- 改 `UR-FEVER-001`：补低体温/寒战/皮肤冰冷，去掉"必须 ≥39℃"门槛
- 每条携带 `未经临床审核` + 出处字符串
- `POLICY_VERSION` → `CN-ADULT-ONLINE-TRIAGE-2026.10-P1`
- 新语料测试类：正例变体、否定、历史、口语变体（如"半边脸都麻了"）
- 同步更新 `docs/INCIDENT_ALLERGY_WARNING_2026-10-01.md` 与 `docs/INCIDENT_NASAL_BOOKING_2026-10-01.md`

### 7.6 Stage 3/4｜暂缓

留待有临床审核能力时启动。在此之前在文档中登记为已知缺口。

---

## 8. 验收标准

每个 Stage 必须满足：

- JDK 17 下 `mvn test` 全量通过（仓库已提交基线 53/53；含在途 D4 用例为 55/55，Stage 2 后按新增用例数递增）
- 每次 commit 只做一件事，message 含变更原因
- 文档先于或同 commit 更新
- 无真实密钥、无个人数据
- 每条新规则可回答："出处是什么？为什么未经临床审核仍上线？"
- 任一 EMERGENCY 输入必须满足：无医生、无预约机会、红色警示、状态 `紧急提示`
- 任一拒答输入必须满足：完整文案等于 `PRESCRIPTION_REFUSAL`、`modelStatus=POLICY_REFUSAL`、`assessments=0`、预约 409、CallLog 为 `合规拒答` 且 `success=true`

---

## 9. 参考来源

| 来源 | 用途 |
| --- | --- |
| `digital.nhs.uk/.../nhs-pathways-clinical-enquiries-management-process` | 临床层级提问、disposition、临床治理；不以诊断为目标 |
| `nhs.uk/conditions/sepsis` | 脓毒症公众版红旗 |
| NICE `NG253` §1.1 | 脓毒症识别；"may not have a high temperature" |
| SDCEP Dental Abscess | 牙源性感染扩散转诊标准 |
| `bmj.com/content/bmj/351/bmj.h3480.full.pdf`（Semigran 2015） | 23 个症状检查器：分诊 57%（33–78%），诊断首命中 34% |
| `pmc.ncbi.nlm.nih.gov/articles/PMC8672574` | under-triage 风险因素 |
| `pmc.ncbi.nlm.nih.gov/articles/PMC8463357` | 创伤 under-triage 综述 |
| `国卫办医发〔2022〕2号` 第二十一条 | AI 不得替代医师（代码注释已引用） |

**所有指南原文为英文，中文表述映射未经临床审核——这是当前最需要留痕的环节。**

外部文献的分诊性能数字（33%–78% 等）来自英文症状检查器研究，与本系统的可比性未经评估。引用它们是为了说明"分诊准确率天然有限"这一量级参照，**不得用于声称本系统达到或超过任何基准**。

---

## 10. 上线阻断项（无临床团队时不可宣称）

1. 规则集未经临床审核
2. 无前瞻性验证，无敏感度/特异度数据（见更正 3）
3. 生命体征未采集
4. 无运营中的人工兜底——`POST /api/triage/sessions/{id}/human-review` 仅记录申请，不保证响应（`PRESCRIPTION_REFUSAL` 文案已如实声明"演示系统仅记录申请，不保证实时响应"）
5. 患者为自身症状（`forSelfConfirmed`）无核实机制

README 与前端应保留"本建议不构成诊断、处方或治疗意见"及"未经临床审核"标识。

---

## 11. 交接检查清单

接手方按序执行：

1. `git status` 确认工作树干净（Stage 0 已提交）；若出现意外修改，先查清来源再继续
2. 读 `AGENTS.md`
3. 读本文 `§0` 硬约束、`§2` 现状；再读 [KNOWN_ISSUES_PRECLINICAL.md](KNOWN_ISSUES_PRECLINICAL.md) 缺陷本体
4. 向 owner 取得 `§6` 三个裁定（Q1 阻塞 `D5`；Q2 阻塞 Stage 2）
5. 从 `§7.4` Stage 1 的 `D1` / `D2` / `D4` 开始（不依赖裁定）
6. 每完成一个 Stage 独立提交，并同步更新本文与 `KNOWN_ISSUES_PRECLINICAL.md`

---

**本文档不构成临床认可。** 所列规则覆盖与改动方向均需具备资质的临床人员审核后方可用于真实患者场景。
