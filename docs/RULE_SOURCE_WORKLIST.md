# 规则出处工作单：15 条安全规则逐条匹配公开来源

> **性质**：本文**只做出处匹配与缺口登记，不改任何规则代码，不构成临床认可**。
> 全部结论**未经临床审核**。规则表由 `TriageSafetyPolicy` 的静态正则构成，与知识库完全解耦——本文的出处不会自动进入 `grounded`，也不会自动改变任何判定。
> **基线**：`POLICY_VERSION = CN-ADULT-ONLINE-TRIAGE-2026.10-P6`，15 个规则码。
> **配套**：[`docs/KNOWLEDGE_SOURCES.md`](KNOWLEDGE_SOURCES.md)（收录标准与许可）、[`tools/knowledge-sync/sources.json`](../tools/knowledge-sync/sources.json)（源登记，权威出处列表）、[`docs/KNOWN_ISSUES_PRECLINICAL.md`](KNOWN_ISSUES_PRECLINICAL.md)（缺陷本体）
> **owner**：本文作者只负责来源侧。规则表达式的任何改动属 `triage/**` 单写手范围，须串行交接。

---

## 0. 怎么读这份表

「建议出处」列给出的是**公开来源 id**（对应 `sources.json`），不是"这条规则是对的"。三档覆盖度：

| 档 | 含义 |
| --- | --- |
| **有出处** | 来源明文覆盖该规则的关键表现，且我已读到原文表述 |
| **部分覆盖** | 来源覆盖该表现的一部分，规则里的其余关键词无来源支撑 |
| **无出处** | **找不到任何已登记来源支撑**。如实留白，不硬凑 |

「建议动作」只有四类：`保留`、`补出处注释`、`需临床审核后修订`、`需补来源`。**没有一条建议是"删除"**——`HANDOVER_TRIAGE_SAFETY_2026-10-02.md` §0 第 2 条要求不得放宽现有规则，缺口靠新增而非删改。

---

## 1. 现状快照（P6）

| 机制 | 说明 |
| --- | --- |
| 声明方式 | `emergency(code, category, expr, reason)` / `urgent(...)` 共 **14 条**（9 EMERGENCY + 5 URGENT），外加 1 条派生规则 → **15 个规则码** |
| 跨小句组合 | 3 个规则码另有 `Combination` 变体：`ER-PREGNANCY-001`（孕产状态+危险征象、产后大出血）、`ER-FACE-SPREAD-001`、`UR-PREGNANCY-001`（孕产状态+明显出血） |
| 部位限定 | `siteSymptom(site, maxGap, symptom)`：填充字符受否定词保护，否定词落在部位与症状之间时不触发 |
| 跨逗号 | `gap()`：**只有** `ER-FACE-SPREAD-001` 用。`assess()` 先按标点切小句，这是 D9 的结构性成因，不应由规则绕过 |
| 否定 | `NEGATION` 词表 + 匹配点前 **14 字**窗口；D11 起另有 `ADJACENT_NEGATION` 覆盖全部第一步规则 |
| 历史语境 | `HISTORICAL` 词表 + `CURRENT_RESET` 复位 |
| 派生规则 | `ER-ALLERGY-001` = `FOOD_REACTION` ∧ `GENERALIZED_RASH`，不在 `RULES` 列表里，由 `assess()` 单独合成 |
| 表达式规模 | 420 字分块、`52` 词检索词表均与规则无关（见 `KNOWLEDGE_PIPELINE.md`） |

---

## 2. 逐条匹配

### 2.1 EMERGENCY（9 条）

| 规则码 | 关键表现（现状关键词摘要） | 现状出处 | 建议出处 | 覆盖度 | 建议动作 |
| --- | --- | --- | --- | --- | --- |
| `ER-AIRWAY-001` | 舌头/舌体/咽喉/喉头 + 肿/水肿；无法吞咽、吞咽不了、窒息、说不出话 | 无 | `nhs-anaphylaxis`（"嘴唇、口腔、咽喉或舌突然肿" → 999；"咽喉发紧或吞咽困难" → 999）、`sdcep-dental-abscess`（floor-of-mouth swelling / difficulty swallowing → 立即急诊转诊） | **部分覆盖** | `补出处注释`；另见 §4.1 —— 规则缺「嘴唇/口唇/面唇」 |
| `ER-FACE-SPREAD-001` | 面部肿胀 +（张口受限/吞咽/呼吸）同小句；或口底肿/口底三角区 | **有**（注释引 SDCEP Dental Abscess + NHS dental abscess，两条均已核实） | `sdcep-spreading-infection`、`sdcep-dental-abscess` | **有出处** | `保留`；可把注释里的泛指"NHS dental abscess"精确到 `sdcep-dental-abscess` 源 id |
| `ER-BREATHING-001` | 严重呼吸困难、呼吸困难、喘不上气、喘不过气、不能平卧、口唇/嘴唇发紫、咯血 | 无 | `nhs-anaphylaxis`（呼吸急促或呼吸困难 → 999；口唇/舌/皮肤发青灰苍白）、`nhs-sepsis`（difficulty breathing）、`nhsinform-sepsis`（severe breathlessness） | **部分覆盖**（"呼吸困难"有；**"不能平卧"、"口唇发紫"无来源**） | `补出处注释`；「不能平卧」「口唇发紫」两条需 `需补来源` |
| `ER-CIRCULATION-001` | 急性/持续/剧烈胸痛及全部口语变体（含裸 `胸痛`） | 无 | `who-heart-attack`、`nhc-chest-pain`（已登记，412 需人工核） | **有出处** | `补出处注释` |
| `ER-NEURO-001` | 意识不清/障碍、昏迷、晕厥、口角歪斜、单侧肢体无力、说话/言语不清、突发剧烈头痛、全身抽搐/抽搐/惊厥 | 无 | 卒中部分：`ndcpa-stroke`（口角歪斜、肢体无力、言语障碍）。意识改变：`nhs-sepsis`、`nhsinform-sepsis`、`nhs-england-sepsis-easyread`。晕厥：`nhs-fainting` | **部分覆盖** | `补出处注释`；**「突发剧烈头痛」无来源** → `需补来源` |
| `ER-BLEEDING-001` | 伤口大量出血、出血不止、呕血、大量咯血、便血不止、黑便伴头晕 | 无 | **无** | **无出处** | `需补来源`（见 §4.2） |
| `ER-TRAUMA-001` | 骨头外露、骨头穿出皮肤、开放性骨折、肢体断裂 | 无 | `who-basic-emergency-care`、`nhs-broken-arm`（覆盖骨折就医路径，未覆盖"开放性/骨外露"这类需急诊转运的表述） | **部分覆盖** | `补出处注释` |
| `ER-POISON-001` | 自杀、自残、不想活、服药过量、药物过量、中毒、误服农药 | 无 | **无** | **无出处** | `需补来源`（见 §4.2） |
| `ER-PREGNANCY-001` | 怀孕/孕期 + 大量出血/剧烈腹痛；产后大出血。另有跨小句组合 | 无 | `nhs-pregnancy-stomach-pain`（实测 200；阴道出血、**腹痛严重或休息 30–60 分钟不缓解**→立即联系产科；六类严重病因含胎盘早剥、子痫前期）、`nice-ng255`（孕产脓毒症）、`cdc-hearher-maternal-warning-signs`（**待人工核实**） | **有出处**（三层） | `补出处注释`；**注意 NG253 不得用于本条**（范围不含孕产） |

### 2.2 URGENT（5 条）

| 规则码 | 关键表现 | 现状出处 | 建议出处 | 覆盖度 | 建议动作 |
| --- | --- | --- | --- | --- | --- |
| `UR-PREGNANCY-001` | `可能怀孕`（带 `(?<!不)`） | **有**（D10-C2 注释，说明是产品范围裁定而非临床结论） | — | **不适用** | `保留`。**必须写明：这是"范围与产品裁定"，不是临床指南结论**；任何文档不得把它表述为临床依据 |
| `UR-TRAUMA-001` | 疑似骨折、骨折、摔断、骨头断、明显变形、不能活动 | 无 | `nhs-broken-arm` | **有出处** | `补出处注释` |
| `UR-FACE-SWELLING-001` | 单独面部肿胀（不伴气道/呼吸表现） | **有**（注释：single facial swelling is not an emergency on its own） | `sdcep-spreading-infection`（cellulitis/肿胀需识别，但**全身受累**才需紧急处置 → 支持"单独肿胀走 URGENT 而非 EMERGENCY"） | **有出处** | `保留`。这是 Q5 裁定的规则化落点，注释已诚实写明未经临床审核 |
| `UR-FEVER-001` | 持续高热、高烧不退、体温 39/40 | 无 | `nice-ng253`（**"may not have a high temperature"**）、`sdcep-dental-abscess`（**<36℃ 或 >38℃ 即提示全身受累**，且「absence of pyrexia does not preclude」） | **与指南冲突** | **`需临床审核后修订`**：去掉"必须 ≥39℃"门槛、补低体温/寒战。`ec3be90` 的 `FEVER_CAVEAT` 只是文案层缓解，未改判定 |
| `UR-PAIN-001` | 剧烈腹痛、腹痛难忍、疼痛难忍 | 无 | 孕期：`nhs-pregnancy-stomach-pain`。**非孕产成人：无已登记来源** | **部分覆盖** | `需补来源`（见 §4.2） |

### 2.3 派生规则（1 条）

| 规则码 | 关键表现 | 现状出处 | 建议出处 | 覆盖度 | 建议动作 |
| --- | --- | --- | --- | --- | --- |
| `ER-ALLERGY-001` | `食物过敏 \| 吃了/吃完…过敏/起疹/红疹/红肿/风团` **且** `全身/大面积/大片/遍身…红肿/红疹/红点/皮疹/风团/荨麻疹/起疹` | 无 | `nhs-anaphylaxis`、`nhsinform-anaphylaxis` | **规则比来源更窄** | **`需临床审核后修订`**：见 §3.1 |

---

## 3. 出处比对暴露的两处规则偏窄（读码推断，**待实测**）

### 3.1 `ER-ALLERGY-001` 要求"食物反应 **且** 全身皮疹"，比公开来源窄

`nhs-anaphylaxis` 的立即处置判据（Call 999）中，**皮疹并非必要条件**：

> 嘴唇、口腔、咽喉或舌突然肿 · 呼吸急促或呼吸困难 · 咽喉发紧或吞咽困难 · 皮肤/舌/唇发青灰苍白 · 突然极度困惑、嗜睡或眩晕

而本规则要求同时命中"食物反应"与"全身皮疹"。组合起来看，下面这类描述可能**一条规则都不触发**：

| 输入 | 现状推断 | 依据 |
| --- | --- | --- |
| `吃了海鲜，嘴唇肿了，呼吸有点急` | 不触发 `ER-ALLERGY-001`（无全身皮疹词）；不触发 `ER-AIRWAY-001`（无舌/咽喉+肿，且"嘴唇"不在部位词表）；不触发 `ER-BREATHING-001`（无"呼吸困难/喘不上气"） | `ER-AIRWAY-001` 部位词为舌/舌体/咽喉/喉头；`GENERALIZED_RASH` 要求全身类部位词 |

**这不是"要去掉皮疹要求"的建议**——`HANDOVER §0` 第 2 条禁止放宽。但它是一条**必须由临床审核判断的候选缺口**，且有明确出处支撑。建议交 `triage/**` owner 时附带两个用例：

```
"吃了海鲜，嘴唇肿了，呼吸有点急"     // 现状推断：无任何规则触发
"吃���芒果后嘴唇和喉咙都肿了"          // 现状推断：ER-AIRWAY-001 应触发（咽喉+肿）
```

第二条用来确认现有规则在**有**咽喉肿胀词时确实能触发，避免第一条被误判成"整个过敏流程都失效"。

### 3.2 `FOOD_REACTION` / `GENERALIZED_RASH` 的紧邻限制（D8 遗留）

`FOOD_REACTION` = `食物过敏 | (吃(了|完) …{0,16} … 过敏|起疹|红疹|红肿|风团)`，填充受否定词保护且上限 16 字。已知口语 `吃了海鲜全身起了很多红点` 中"起了很多红点"到最近可用词的距离超出 16 字，配对不触发（`KNOWN_ISSUES` D8 条目已登记）。

`nhs-anaphylaxis` 与 `nhsinform-anaphylaxis` 的皮疹描述包含 `swollen, raised or itchy`、`red raised itchy rash`、`风团/荨麻疹` 等形态词，可作为**扩充词形的公开依据**——但**只放宽填充长度是不够的**，需要同时考虑口语变体，且必须经临床审核。**不要单方面改。**

---

## 4. 出处缺口（明确留白）

### 4.1 已识别的"关键词无来源支撑"清单

| 规则 | 无来源支撑的关键词 |
| --- | --- |
| `ER-AIRWAY-001` | 嘴唇/口唇肿胀（部位词表只有舌/舌体/咽喉/喉头） |
| `ER-BREATHING-001` | 不能平卧、口唇发紫 |
| `ER-NEURO-001` | 突发剧烈头痛 |

这三条**不代表规则错**——临床上「不能平卧」「口唇发紫」「突发剧烈头痛」都是公认高危体征。问题是**本项目没有引用任何来源来支撑它们**，属于留痕缺失而非内容缺失。按 `AGENTS.md` 第 3 条与 `HANDOVER §0` 第 1 条，这属于必须补出处的地方。

### 4.2 整条规则无来源

| 规则 | 现状 | 建议候选来源（**均未核实，仅为检索方向**） |
| --- | --- | --- |
| `ER-BLEEDING-001` | 呕血/便血/黑便等消化道出血征象 | NHS `vomiting blood` / 消化道出血类条件页；本轮检索未命中确切 URL，**不写入登记表** |
| `ER-POISON-001` | 自伤与中毒 | 需官方来源（WHO 或国家卫健委）；自杀相关内容还需另配"如何求助"的合规文案，**不得只做拦截不做转介** |
| `UR-PAIN-001`（非孕产成人） | 剧烈腹痛的成人鉴别不能在线完成 | 需权威成人急腹症来源 |

**这三条不写进 `sources.json`**，因为仓库政策要求"定位不到稳定官方链接就不登记，不允许长期保留占位"（`KNOWLEDGE_SOURCES.md §7`）。

---

## 5. 建议登记的新来源（3 条，URL 已核实）

| 建议 id | 来源 | 覆盖 | URL | 核实依据 |
| --- | --- | --- | --- | --- |
| `nhs-anaphylaxis` | NHS Anaphylaxis | `ER-ALLERGY-001`、`ER-AIRWAY-001`、`ER-BREATHING-001` | `https://www.nhs.uk/conditions/anaphylaxis` | 页面内容与"最后复核 2023-06-21"经搜索引擎提取的实时正文核实 |
| `nhsinform-anaphylaxis` | NHS inform Anaphylaxis（苏格兰） | `ER-ALLERGY-001`（ABC 三系统 + 皮疹形态词） | `https://www.nhsinform.scot/illnesses-and-conditions/immune-system/anaphylaxis` | 同上 |
| `nhs-fainting` | NHS Fainting | `ER-NEURO-001` 的晕厥部分 | `https://www.nhs.uk/conditions/fainting` | 页面内容与"最后复核 2023-02-23"经搜索引擎提取的实时正文核实 |

### 5.1 明确**排除**的来源（保持政策一致）

| 来源 | 排除理由 |
| --- | --- |
| Resuscitation Council UK 指南 PDF | 检索到的版本标注 **DRAFT**；`KNOWLEDGE_SOURCES.md §2` 整类排除征求意见稿与未定稿 |
| Anaphylaxis UK | 民间慈善机构，非官方机构或公立医院；`KNOWLEDGE_SOURCES.md §1` 第 1 条只收官方/公立 |
| 某 NHS 地方用药手册（northeast.devonformularyguidance） | 地方性文件，且为转述 NICE CG134；本项目应直接引 NICE 或 nhs.uk |
| 各类商业问答与自媒体 | 政策整类排除 |

---

## 6. 与已登记缺陷的关系

| 已登记项 | 本工作单的结论 |
| --- | --- |
| **D7** 发热门槛与指南冲突 | 出处已配齐（`nice-ng253` + `sdcep-dental-abscess`），两个独立来源都比 39℃ 宽。**修订仍需临床审核**，本工作单不提出具体阈值 |
| **D11** 紧邻否定保护 | 与本文无关（属表达式机制），但**"给全部第一步规则加紧邻否定保护"这条待裁定的残余**在 P6 已落地，注释里仍写"待裁定"，需 owner 清理 |
| **Q5** 脸肿分级 | 已落为 `UR-FACE-SWELLING-001` + `ER-FACE-SPREAD-001` 的组合，出处是 SDCEP，方向与来源一致 |
| **D3/D10** 孕产 | 三层出处已配齐；`UR-PREGNANCY-001` 明确标注为产品裁定而非临床结论 |
| **§5.2** 脓毒症整类缺失 | 仍缺 `ER-SEPSIS-001`。出处已配齐（NICE NG253 + NHS sepsis + nhsinform），**但规则设计需临床审核**，本文只给候选表现，不给正则 |
| **§5.4** P1 急症（糖尿病急症/肾梗阻/眼急症/肢体缺血/脱水） | 出处部分就绪（`nhs-vision-loss` 已核实可覆盖眼急症；`nhs-dvt-blood-clots`、`nhs-hypoglycaemia`、`nhs-kidney-stones` 为 `toVerify` 占位）。**规则设计留待临床审核** |

---

## 7. 给 `triage/**` owner 的移交清单（本文不做）

1. **§2 的 `补出处注释`**：11 条规则当前只有 3 条在代码注释里标了来源，其余 11 条无任何出处留痕。这是纯注释改动，不改行为。
2. **§3.1 的两个用例**：确认"嘴唇肿 + 轻度呼吸困难 + 食物暴露"是否真的不触发任何规则。这是**验证**，不是改动。
3. **§2.2 `UR-FEVER-001` 的修订**：需临床审核后才能改判定；`FEVER_CAVEAT` 是文案层缓解，不等于判定层修正。
4. **§4 的整条无出处规则**：`ER-BLEEDING-001`、`ER-POISON-001` 的来源补齐需要先定位到官方链接，不允许用商业内容凑。
5. **`ER-POISON-001` 的合规配套**：自伤相关内容不能只做"拦截"，需要配"如何求助"的线下指引文案，否则会把患者引向死路。**这一项优先级高于规则本身的出处。**

## 7. 规则数据化后的 `citations` 校验（新增）

规则正在迁到 `backend/src/main/resources/safety-rules.json`，其中已有 `citations: [{id, title, note}]` 与 `coverage` 字段——**这正是本文 §2 想提供的东西**。但出处一旦分散到两个文件，id 拼错就会变成断链，而断链的引用**看起来和有出处一模一样**。

为此新增一条只读检查：

```
cd tools/knowledge-sync && npm run citations
```

它把规则文件里的每个 citation id 拿去比对 `sources.json`，并额外盯两类目测容易漏的情况：来源状态非 `verified`、来源是 `manual`（没人会发现内容漂移）、来源自述为占位条目。

### 7.1 首次运行的结果（对工作树未提交状态，2026-10-02）

规则文件当时为 14 条规则 + 4 条组合、19 条引用，涉及 14 个规则码。**6 条断链或不可用引用**：

| 规则码 | 引用 id | 问题 | 建议 |
| --- | --- | --- | --- |
| `ER-PREGNANCY-001` | `cdc-maternal-warning-signs` | 登记表里是 `cdc-hearher-maternal-warning-signs` | 改 id 为已登记项；且该源仍是 `toVerify` + 403，人工核实后再当正式引用 |
| `ER-PREGNANCY-001` | `nhs-stomach-pain-pregnancy` | 登记表里是 `nhs-pregnancy-stomach-pain` | 改 id 为已登记项 |
| `ER-PREGNANCY-001` | `nhs-ectopic-pregnancy` | **未登记** | 宫外孕页确实值得登记，但须先核实 URL |
| `UR-PREGNANCY-001` | `nhs-ectopic-pregnancy` | 同上 | 同上 |
| `UR-FACE-SWELLING-001` | `nhs-dental-abscess` | 登记表里是 `sdcep-dental-abscess`（同一 NHS Scotland 站点，id 不同） | 改 id 为已登记项 |
| `ER-AIRWAY-001` | `nhc-er-dept-guideline` | **占位条目**：URL 是 `nhc.gov.cn` 首页、状态 `toVerify`、`manual` | 按收录政策（`KNOWLEDGE_SOURCES.md §7`「定位不到稳定官方链接就删除，不允许长期保留占位」）**不应作为正式引用**。改用 `nhs-anaphylaxis`（见下） |

**特别注意最后一条**：这不是 id 拼错，是**把一个明确标注为占位的条目当成了正式出处**。占位条目一旦被引用，"这条规则有出处"就成了假陈述——比没有出处更糟。

### 7.2 `ER-AIRWAY-001` 的推荐替换出处

| 来源 id | 覆盖的 999 判据（原文要点） |
| --- | --- |
| `nhs-anaphylaxis` | 嘴唇、口腔、咽喉或舌突然肿；咽喉发紧或吞咽困难；呼吸急促或呼吸困难 |
| `sdcep-dental-abscess` | floor-of-mouth swelling、difficulty breathing/swallowing → 作为急症立即转诊 |

顺带暴露一个**规则侧的出处不匹配**（不是断链，但同样削弱可追溯性）：

| 规则码 | 现引用 | 问题 |
| --- | --- | --- |
| `ER-NEURO-001` | `nice-ng253` | NG253 适用（16 岁以上非孕产），但它**不是「晕厥」的出处**。建议补 `nhs-fainting`（已登记，含 999 判据：1 分钟内无法唤醒、伴胸痛或心悸、运动中晕厥、因抽搐抖动等），并为「突发剧烈头痛」另补来源（§4.1 已登记为无出处） |

### 7.3 派生规则 `ER-ALLERGY-001` 不在数据文件里

规则文件只有 14 条声明，**不含 `ER-ALLERGY-001`**。核对代码后确认这是**有意的**且安全：该规则的 `FOOD_REACTION` ∧ `GENERALIZED_RASH` 合成逻辑仍在 `TriageSafetyPolicy` 里以硬编码存在，测试也锁定它。

但这带来一个一致性后果：**派生规则在数据文件里没有 `citations` 字段可写**。要么在代码注释里保留出处（现状），要么在数据文件里加一个 `derivedRules` 段落。这是一个需要 owner 明确决定的结构问题，本文不代劳。

---

**本文档不构成临床认可。** 所列规则的出处匹配仅表示「公开来源中有相应表述」，不表示该表述已被本项目采纳，也不表示规则经临床审核。任何临床表述均需具备资质的临床人员审核后，方可用于真实患者场景。