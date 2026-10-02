# 业务知识清单：当前散落在代码里的业务规则

> **性质**：本文**只登记，不修复**。每条给出位置、是否单源、能否成为知识库内容、修复会触碰哪些文件。
> **基线**：行号已按提交 `c3e24ad` 全量重核。`193c6fc` 之后的 `895cf7a`（D3 复裁定，新增孕产边界逻辑）与 `c3e24ad` 使 `TriageConversationService.java` 整体下移约 6 行、`TriagePage.vue` 因新增免责声明下移 1 行，相关引用已全部修正；`RuleBasedTriageEngine.java`、`AssessmentCard.vue` 及其余被引用文件未被这两次提交触及，引用保持不变。行号会随代码继续变动，引用前请复核。
> **定级**：本系统为未经临床审核的原型，无医生执业资质审核、无医院系统对接、无患者数据留痕基础设施。本文不构成合规意见，也不构成临床认可。
> **配套**：[`docs/KNOWLEDGE_PIPELINE.md`](KNOWLEDGE_PIPELINE.md)（临床知识侧）、[`docs/KNOWLEDGE_SOURCES.md`](KNOWLEDGE_SOURCES.md)（来源与许可）

---

## 0. 为什么要有这份清单

项目里最容易被 AI 写坏的，不是算法，是**业务规则被重复写在多处**。`docs/KNOWN_ISSUES_PRECLINICAL.md` 记录的 D4「预约矛盾是结构性的」，本质就是三处各自判断"这次能不能预约"。那次修复靠人工把三处收敛到一个判定函数；下一次新增状态或新科室，同样的矛盾会重新长出来。

本文的作用是：**在写代码之前先说清楚"这类东西现在有几份"。**

---

## 1. 业务实体清单（全部硬编码，无数据源）

| 实体 | 位置 | 事实 |
| --- | --- | --- |
| 7 个科室 + 7 位演示医生 | `catalog/infrastructure/demo/DemoDoctorDirectory.java:16-22` | 构造器字面量，存 `LinkedHashMap`（`:14`）；**无医生表**，`schema.sql` 里没有 doctor 实体 |
| 每位医生号源恒为 20 | `DemoDoctorDirectory.java:25-26` | `remaining=20, total=20` 写死 |
| 排班恒为「明天」 | `DemoDoctorDirectory.java:26` | `LocalDate.now().plusDays(1)`，构建时求值一次；无多日排班、无节假日、无停诊 |
| 出诊时段只有上午/下午 | `DemoDoctorDirectory.java:16-22` | 无更细时段模型 |
| 挂号费 80/60/80/50/60/40/60 | 同上 | 写死 |
| `全部` = 不过滤哨兵 | `DemoDoctorDirectory.java:29-30`、前端 `App.vue:18`、`App.vue:67` | **三处**。且若某科室真的叫「全部」则永不可达 |
| 演示账号白名单 | `identity/application/DemoLoginService.java:19-21` | 仅 `admin`/`zhangsan`/`lisi`；**`:21` 只检查密码非空**，任意非空密码通过 |
| 患者身份 | `DemoLoginService.java:23-25`、`shared/security/JwtService.java:24` | JWT `sub` 是**显示名**（`张三`），不是患者 ID。两位同名者共用全部历史；无患者实体、无知情同意记录 |
| 令牌有效期 | `JwtService.java:24` | 硬编码 `28800` 秒 = 8 小时；密钥未配置时每进程随机 → **重启即全员掉线** |
| 前端重复的演示账号 | `App.vue:10,17,29,31,39`、`frontend/src/api.ts:12,15` | 账号、口令、显示名三处复制了 `DemoLoginService` 的硬编码 |

---

## 2. 科室词表：8 处独立来源，无共享常量、无 API

| # | 位置 | 科室集合 |
| --- | --- | --- |
| 1 | `DemoDoctorDirectory.java:16-22` | 呼吸内科、消化内科、心血管内科、骨科、神经内科、全科医学科、妇科 |
| 2 | `RuleBasedTriageEngine.java:226` `candidatesFor` | 神经内科、消化内科、呼吸内科、骨科、妇科 |
| 3 | `RuleBasedTriageEngine.java:157-158` | `急诊科`、`全科医学科`（默认） |
| 4 | `StructuredDecisionModel.java:39` `DEFAULT_DEPARTMENT` | `全科医学科` |
| 5 | `InMemoryKnowledgeCatalog.java:34-40` `MEDICAL_TERMS` | 含 `耳鼻喉科`，无 `心血管内科`、无 `全科医学科` |
| 6 | `App.vue:20` `depts` | 全部、消化内科、心血管内科、呼吸内科、骨科、神经内科、妇科 |
| 7 | `backend/src/main/resources/knowledge/*.md` `主题：` 行 | 骨科、妇科、呼吸内科、消化内科、急诊、红旗症状 |
| 8 | `RuleBasedTriageEngine.java` 两处科室话术 switch（`guidedFallback` / `fallbackAnswer`） | 呼吸内科、神经内科、消化内科、妇科、骨科 |

**已核实的具体矛盾：**

- **`全科医学科` 不在前端筛选列表**（`App.vue:20`）。它是引擎最常输出的默认科室（`RuleBasedTriageEngine.java:157-158`），患者在预约页**无法按引擎实际推荐的科室筛选**。
- **`心血管内科` 有医生、零路由关键词**（`DemoDoctorDirectory.java:18` 有 `d3`，`candidatesFor` 无对应正则）→ 分诊永远推不出来，只能走 walk-in 独立预约。
- **`耳鼻喉科` 在 52 词检索词表里，但无路由、无医生**（`InMemoryKnowledgeCatalog.java:38`）→ 能被知识检索命中，却永远无法作为推荐科室输出。
- **检索词表与路由词集不重合**：`恶心`、`呕吐`、`流鼻涕`、`鼻塞`、`打喷嚏`、`出血`、`高热` 只在检索侧（`MEDICAL_TERMS`），不在 `candidatesFor` 的路由关键词里。
- **没有任何 `GET /api/departments`**。已核对全部 10 个 Controller，`catalog` 模块只有 `DoctorController.java:13` 的 `GET /api/doctors`。前端 `depts` 数组是全系统唯一的科室目录，且由客户端权威。

> **修复会触碰**：`DemoDoctorDirectory`、新增 `/api/departments`、`App.vue`、`InMemoryKnowledgeCatalog`（词表）、`RuleBasedTriageEngine`（路由 + 两处话术 switch）。**其中 `RuleBasedTriageEngine` 与 `TriageConversationService` 正处于他人单写手任务范围内，不应并行修改。**

---

## 3. 可预约判定：3 份独立实现，今天一致纯属巧合

| 实现 | 位置 | 形式 |
| --- | --- | --- |
| 后端权威 | `triage/domain/Disposition.java:33` `isBookable` | 排除 `紧急` / `尽快就医` / `待补充信息` |
| 后端入口 | `booking/application/BookingApplicationService.java:28` | 调 `isBookable`，否则 409 |
| 后端事务层 | `booking/application/SimulationBookingService.java:56` | 只按**会话状态**阻断 `紧急提示` / `待补充信息`，不看 `riskLevel` |
| 前端 | `frontend/.../AssessmentCard.vue:12` | `!['待补充信息','紧急','尽快就医'].includes(riskLevel)` |

三处风险：

1. **否决表而非白名单**（`Disposition.java:34`）：新增任何 riskLevel 字面量都**默认可预约**。
2. **前端多两个条件**：`AssessmentCard.vue:11-12` 还要求 `version === latestVersion` 与 `grounded`，后端这两层没有 → 界面可以比 API 更严，界面更严时用户会看到"没有预约按钮"而无解释。
3. **两份前端模型状态词表互不一致且都不完整**：
   - `AssessmentCard.vue:35` 处理 `LIVE` / `SAFETY_RULE` / `EVIDENCE_BLOCKED` / `VALIDATION_BLOCKED` / `FALLBACK`
   - `TriagePage.vue:31-39` 处理 `SAFETY_RULE` / `LIVE` / `LIVE_UNGROUNDED` / `DEMO*` / `FALLBACK*` / `VALIDATION_BLOCKED`
   - **两处都不处理 `POLICY_REFUSAL`**（诊断/处方拒答状态）。于是一次合规拒答在结果卡上显示为「本地规则回答」，在会话页显示为「未生成模型回答；建议补充信息或人工咨询」——**把合规拒答说成了"没生成"，与 `RuleBasedTriageEngine.java:52-53` 的真实文案矛盾。**
   - `TriagePage.vue` 漏掉 `EVIDENCE_BLOCKED`；`AssessmentCard.vue` 漏掉 `POLICY_REFUSAL` 与 `DEMO*` / `LIVE_UNGROUNDED`。

> **修复会触碰**：`Disposition`、`AssessmentCard.vue`、`TriagePage.vue`。前端两个文件在本轮他人任务中被改过，需串行。

---

## 4. 会话状态：7 个字面量，4 个受管、3 个游离

| 字面量 | 产生位置 | 消费位置 |
| --- | --- | --- |
| `紧急提示` | `Disposition.java:43` | `TriageConversationService.java:66`（终态，拒绝继续）、`SimulationBookingService.java:56` |
| `建议尽快就医` | `Disposition.java:44` | 仅前端 `TriagePage.vue:110` |
| `待补充信息` | `Disposition.java:45`、另见 `RuleBasedTriageEngine.java:113` 直接写字面量绕过常量 | `SimulationBookingService.java:56`、`TriagePage.vue:109` |
| `已完成分诊` | `Disposition.java:46` | **`OverviewMapper.java:10` 写死在 SQL 里**、前端 `TriagePage.vue:96` |
| `进行中` | `MybatisTriageStore.java:33` | 无任何判定，仅前端显示 |
| `处理中` | `TriageConversationService.java:74` | 无任何判定 |
| `待重试` | `TriageConversationService.java:99` | 无任何判定 |

**最脆的一处**：`OverviewMapper.java:10` 把 `已完成分诊` 写进 `@Select` 注解——

```java
@Select("SELECT COUNT(*) FROM triage_session WHERE status='已完成分诊'") int completedSessions();
```

改动 `Disposition.java:46` 的常量，管理端"已完成"指标**静默归零，不抛异常、不失败**。这是全系统唯一一处业务口径写死在 SQL 字符串里。

另外三个游离状态从未被任何门禁检查：`处理中` 或 `待重试` 的会话能通过 `SimulationBookingService.java:56` 的状态检查，目前只是因为 `latestResult` 返回空或旧结果才没出事。

---

## 5. 口径重复与不可追溯项

### 5.1 免责声明：后端 3 处 + 前端 4 处 + 语料 2 处，无单源

| 位置 | 文案要点 |
| --- | --- |
| `RuleBasedTriageEngine.java:114` | 拒答场景：不提供诊断/处方/治疗建议 |
| `RuleBasedTriageEngine.java:198` | 多科室候选场景 |
| `RuleBasedTriageEngine.java:199` | 普通场景：仅辅助分诊和挂号参考 |
| `TriagePage.vue:107` | 前端预问诊说明 |
| **`TriagePage.vue:108`** | **孕产边界（新增于 `895cf7a`）**：不提供孕产期常规预问诊；要求孕产/近期分娩者停止普通分诊；严重腹痛、明显出血立即急诊。并明写「勾选上方选项只是您的一次性自我声明，不是系统对孕产风险的确认或排除」 |
| **`TriagePage.vue:113`** | **资格勾选标签的二次声明（新增于 `895cf7a`）**：以上为一次性自我声明，系统不会再次核实；声明与后续描述不一致时以线下医疗人员判断为准 |
| `BookingPage.vue:8` | 号源为演示数据、不产生真实就诊凭证 |
| `VisitsPage.vue:8,11` | 症状时间线非正式病历 |
| `AdminPage.vue:19,22` | 演示版声明 + 尚未连接医院排班 |
| `backend/src/main/resources/knowledge/08-fracture-triage.md:5` | 骨折语料自带免责 |

**口径一致性进展**：`895cf7a` 之后，孕产边界已在勾选处（`TriagePage.vue:113`）、会话内常驻声明（`:108`）与建会话 400（`TriageConversationService.java:39-45`）三处一致表述，**均不表述为"已确认排除孕产"**——这一点已修好。但它同时新增了两处文案（常驻声明 + 标签内二次声明），使"免责声明无单源"从本文表格内的 8 处（后端 3 + 前端 4 + 语料 1）增至 9 处（后端 3 + 前端 5 + 语料 1），另加勾选标签内嵌 1 处，**合计 10 处仍无单源**。

### 5.2 一个被凭空编造的指标

```java
// observation/application/AdminOverviewService.java:28
knowledge.documents().size(), calls.calls().size() * 4);
```

管理端仪表盘的"工具调用总数"= **CallLog 条数 × 4**。没有任何依据，是全 app 唯一一个非实测计数的数字。`AdminOverviewService.java:26` 的 `acceptanceRate` 是真实除法（`fromTriage / sessions`），与这一项性质不同。**若对外演示或写入任何材料，这个数字必须先去掉或标注为占位。**

### 5.3 资格声明被丢弃

- 门禁只在建会话时校验一次：`TriageConversationService.java:39`（`adultConfirmed` / `forSelfConfirmed` / `notPregnantConfirmed` 三者全为真才放行）。后续轮次不再校验。
- `18` 岁只存在于界面文案（`TriagePage.vue:113`），后端 `Eligibility.adultConfirmed` 是裸 `boolean`（`triage/domain/TriageRecords.java:22`）→ **年龄从未被采集**。
- 一个复选框同时充当三项声明：`TriagePage.vue:48` 把同一个 `eligible` 值填进三个布尔。
- 落库只存时间不存内容：`TriageMapper.java:23` 的 `INSERT INTO triage_eligibility(session_id,confirmed_at)`，三个布尔被**丢弃**；全库无任何 `SELECT` 读该表（`schema.sql:43` 是唯一另一处出现）。

### 5.4 `humanReviewRecommended` 的准确状态（此前口径需要更正）

**它不是死字段，也不是活字段，精确表述是：后端零读取，前端在消费。**

- 后端：全库仅 `shared/model/Models.java:16-17` 的字段声明，无任何生产代码读取。
- 前端：`AssessmentCard.vue:46` 用它决定是否显示「申请人工导诊」按钮。

因此不得写成"死字段"（那会漏掉前端行为），也不得写成"已接通"（那会掩盖后端不参与判定）。合规拒答场景下该字段为假，这条前端分支不会生效。

### 5.5 `120` 三处硬编码

`TriageSafetyPolicy.java` 内两处（急症指引文案）+ `AssessmentCard.vue:37` 的 `<a href="tel:120">`。无常量。

### 5.6 知识与工具的审核状态只在内存

- `schema.sql` 只有 `sim_*` 与 `triage_*` 表，**无知识相关数据表**。管理员通过 `AdminKnowledgeController.java:30,35` 新增的知识与 `InMemoryKnowledgeCatalog` 的 `READY`/`PENDING_REVIEW` 状态**重启即丢**。
- `KnowledgeDocument`（`Models.java:27`）只有 `{id,title,body,chunks,status,updatedAt}`，**没有来源 URL、版本、许可、审核人、抓取时间**字段——即使知识留在库里，也无法回答"这段话出自哪里、谁审过"。

### 5.7 其他写死的数值

| 值 | 位置 |
| --- | --- |
| 症状描述 1–2000 字 | `TriageConversationService.java:69-70` |
| 标题 24 字 / 预览 120 字 | `TriageConversationService.java:73-74` |
| 人工导诊理由 500 字 | `TriageConversationService.java:132` |
| 工具入参 2200 字 | `tools/application/HospitalToolExecutor.java:81` |
| 幂等键 ≤64 字符 | `SimulationBookingService.java:43` |
| 置信度阈值 35 / 55 / 60 / 72 / 100 | `RuleBasedTriageEngine.java:210-214` |

---

## 6. 可知识库化的业务内容（候选，本轮不实施）

| 候选内容 | 来源 | 进 RAG 吗 |
| --- | --- | --- |
| 科室–症状关键词映射 | `RuleBasedTriageEngine.java:226` | 否，属路由配置 |
| 科室–导诊话术模板 | `RuleBasedTriageEngine` 两处 switch | 否，属输出模板 |
| 状态机与门禁规则表 | 本文档 §3 §4 | 否，属控制流 |
| 号源/预约/幂等语义 | `SimulationBookingService` | 否，属接口契约 |
| **适用范围与合规边界**（不得首诊、AI 不得替代医师、AI 不得自动生成处方、辅助决策属 III 类医疗器械） | `docs/KNOWLEDGE_SOURCES.md` §5 的监管文件 | **否**。与 `KNOWLEDGE_SOURCES.md §6` 同一判断：合规知识属文档层，不属患者对话层 |

**结论：当前没有一条业务知识适合做成患者可读的知识语料。** 业务规则的正确归宿是**数据文件 + 单一判定函数**，不是检索库。把它塞进 RAG 只会制造第 9 份副本。

**另一条与业务规则直接相关的检索事实**（完整版见 [`docs/KNOWLEDGE_PIPELINE.md §6.1`](KNOWLEDGE_PIPELINE.md)）：`grounded` 只表示"有片段过了阈值"，**不表示命中了医学概念**。

- 词法路径：`termScore` 的分母只取 52 词表筛出的 `queryTerms.size()`（`InMemoryKnowledgeCatalog.java:89,107`），全词表外的查询只剩 bigram 权重与权威加分
- 语义路径：启用 embedding 后 `grounded` 可由向量余弦单独产生（`HybridKnowledgeCatalog.java:43`），词表全程不参与

因此 `RuleBasedTriageEngine.java:215`（`bookable`）与 `TriageConversationService.java:138`（号源可用性）**都不构成"知识依据充分"的证据**——Q4「依据达标才附医生」的支点比表面看起来弱。`npm run verify` 的词表外守卫用于盯住词法路径，语义路径无法离线验证。

---

## 7. 建议的处理顺序（供 owner 排期，本轮不执行）

> **在途状态**：基线 `c3e24ad` 时，以下第 1–5 项涉及的文件**仍在 opencode 单写范围内、本轮未完成**。本文只登记，不催促。

1. **先删编造指标**：`AdminOverviewService.java:28` 的 `* 4`。
2. **补 `POLICY_REFUSAL` 显示**：两个前端组件的模型状态映射（`AssessmentCard.vue:35`、`TriagePage.vue:31-39`）——这是**事实性错误**，合规拒答被说成"未生成模型回答"，优先级高于一切重构。
   - 已知不变量：拒答路径**不保存任何 assessment**（现有测试断言 `assessments.size()==0`），故 `AssessmentCard.vue:35` 的兜底当前**不可达**。处理时应补分支并把该不变量写进注释，避免下一个人把它误判成活 bug 的同等紧急项；`TriagePage.vue:38` 的兜底可达，属活 bug。
3. **把 `已完成分诊` 从 SQL 里拿出来**：改为按 `Disposition` 常量传入或用状态枚举列。
4. **加 `GET /api/departments`**，前端 `depts` 改为从接口取；顺带补上 `全科医学科`。
5. **把 `isBookable` 改为白名单**，并让前端从后端字段派生而非硬编码数组。
6. 以上都完成后再考虑：路由词表数据化、资格声明落库、多日排班。

> 第 1–5 项都会改动 opencode 当前单写手范围内的文件（`RuleBasedTriageEngine` 及其测试、`AssessmentCard.vue`、`TriagePage.vue`）。**必须串行交接，不得并行。** GPT 除 D4 外只出裁定、不写生产代码，不构成并行写入方。

---

**本文档只做登记，不构成合规意见，也不构成临床认可。** 任何临床表述均未经具备资质的临床人员审核。