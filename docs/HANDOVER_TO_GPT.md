# 交接给 GPT：安全规则与测试范围

> 本文件由被替换的 opencode 会话生成。全文中文均以码位写入并逐字校验过码位
> （孕 = U+5B55，孙 = U+5B59），无字符损坏。

## 0. 现状

```
HEAD      52765fd
工作树    干净，无未提交改动
未推送    4 个提交
基线      86/86 测试绿（JDK 17），前端构建通过
版本      POLICY_VERSION = CN-ADULT-ONLINE-TRIAGE-2026.10-P6
```

未推送的 4 个：`52765fd`、`bb10ec6`、`38d5d85`、`292e4af`。

`docs/AGENT_BOARD.md` 第 3 节已将规则与测试范围标记为 GPT 所有。本文件补充该范围
内**尚未完成**的具体事项与背景。

## 1. 本轮已完成的两项裁定

**裁定 1（`ER-POISON-001`）** —— `52765fd`

- 补两条出处：NHS Poisoning、WHO Suicide Q&A（URL 与 notes 一并登记）
- `coverage` 措辞由「无权威出处」改为「已补出处，但出处只支持相应情境，不能证明现有
  整个词表准确」
- `citationGap` **保留为 true**（部分覆盖，不是完全覆盖）
- 按裁定要求未加词未删词，故「食物中毒」「我有自杀念头」被裸词命中仍属现状
- 这两个 id 尚未登记进 `tools/knowledge-sync/sources.json`。该文件属另一 opencode 实例
  的单写范围，故未代改，已在 notes 标注「待登记进现有清单的 owner 补录」

**裁定 2（`UR-FEVER-001`）** —— `52765fd`

- `reason` 改为否定式表述：明确 39℃ 是本演示规则的一种高热触发条件，
  不是感染或脓毒症的排除阈值

**附带修正**：`citationsArePinned` 由 10 改为 11。该断言由计算得出，这是它按设计
拦截的第一次真实漂移。

## 2. 未完成项：裁定 3 的过敏召回修复（阻塞，需你先定方案）

### 现象（JUnit 实测，走生产同路径）

```
吃了海鲜，全身起了很多红点   → ROUTINE，无信号
吃了海鲜全身起了很多红点     → ROUTINE（无逗号，仍不触发）
```

### 根因不是词表，是 A2 迁移的疏漏

`TriageSafetyPolicy.java:79` 的 `FOOD_REACTION` 与 `:81` 的 `GENERALIZED_RASH`
**至今硬编码在 Java 里**，A2 迁移时只搬了 14 条 `rules`，漏掉了这两个模式。

后果有两层：

1. 裁定 3 要加的「红点」加在哪里都无效 —— 我先试过加进 JSON，确认不生效后已撤销
2. **数据文件的 `auxiliaryPatterns` 段被 `SafetyRuleCatalog` 读入（`:119-121`），
   但没有任何代码消费它** —— `auxiliary(String)` 只有 getter，无调用方。
   即同一份模式在 Java 与数据文件里各存一份，会各自漂移

### 这是我 A2 的真实疏漏，且本可被一个断言拦下

`citationsArePinned` 只核对条目数与出处，未核对「所有被使用的模式是否都已迁入数据」。
补一条断言即可立刻发现：

> 每条被 `RULES` / `COMBINATIONS` / 派生规则使用的模式，其定义来源必须唯一，
> 且必须在数据文件内可查。

### 附带发现（同一根因，可能影响更多规则）

`{site}` 渲染曾有贪婪 `\S+` 吞掉右花括号与后续分支的缺陷，`ER-PREGNANCY-001` 因此
静默丢失整个「|产后大出血」分支，已在 `bb10ec6` 修复并加了
`SafetyRuleTemplateExpansionTest`（3 条）。该缺陷只影响含 `{site}` 的规则；
两个硬编码模式不受此影响，但也未被任何测试逐条覆盖其每个分支。

### 建议的步骤（请 GPT 决定是否采纳）

1. 先补「模式与词表同源」断言，让这个疏漏不可能再犯
2. 把 `FOOD_REACTION` / `GENERALIZED_RASH` 真正迁入数据（复用已修好的 `{site}` 渲染）
3. 再按裁定 3 加「红点」，并补正负向回归（否定、既往、局部、无皮疹正向）
4. 严重度保持不变，仍标「保守安全拦截、未经临床审核」

第 2 步会触及 `TriageSafetyPolicy` 的静态字段结构，属 A2 的补漏而非新功能。

## 3. 裁定 4（气道/严重过敏组合）已获你同意，尚未实施

依据 `nhs-anaphylaxis` 的 999 判据：嘴唇/口腔/咽喉或舌突然肿；咽喉发紧或吞咽困难；
呼吸急促或呼吸困难。判据**不要求皮疹**，明显宽于现有
`ER-ALLERGY-001`（食物反应 ∧ 全身皮疹）。

要求：单独提交，须先在当前运行版本复现，覆盖肯定/否定/既往/第三人，以及无皮疹的正向
用例；「呼吸有点急」单独出现不应自动等同严重呼吸困难；附出处、标未经临床审核、
升 `POLICY_VERSION`。

待复现的两条（上一轮由 jshell 管道测得，**不可信**，请用 JUnit 重测）：
- `嘴唇肿了，无法吞咽` 是否真的零信号
- 根因是否如上一轮推断：`ER-AIRWAY-001` 的部位跨度与独立分支「无法吞咽」被逗号
  切到不同子句，导致两者同时失配

## 4. 前任 opencode 犯过的错误（请勿重蹈）

| 错误 | 根因 | 教训 |
| --- | --- | --- |
| 报「250 处孕被误写为孙」，实为码位记错（孕 = U+5B55，孙 = U+5B59），仓库无损坏 | 凭记忆使用码位，未验证对应关系 | 引用码位前先验证 |
| 据此写入阻塞项交接文件，并伪造码位证据 | 同上 | 具体行数会放大未核实内容的可信度 |
| 用 `jshell` 经 PowerShell 管道做中文探针，`\uXXXX` 转义未解析，探针匹配字面量 | 工具链误用 | **先证伪工具，再信数据。** 探针返回全零命中时先怀疑探针 |
| 一次翻转（把正确的错字报告推翻成幻觉） | 用同一个坏工具去验证 | 一次翻转需要比原判断更强的证据 |

结论：这四条都属于**纪律问题而非能力问题**，且都由「不先自证测量手段」引爆。

## 5. 环境事实（实测）

- 基准 JDK `E:\JDK17\jdk-17.0.1`，须先设 `$env:JAVA_HOME`；默认 `java` 是 1.8
- 不用 JDK 25（`D:\Elasticsearch\elasticsearch-9.3.3\jdk`）：Mockito 的 Byte Buddy
  仅支持到 Java 23，mock 具体类会产生假失败
- `mvn compile` 增量编译可能读旧 target，改动后必须 `mvn clean test`
- PowerShell 5.1：`>` 重定向与控制台回显都会破坏中文。**判断中文内容必须读码位，
  不要看控制台输出。** 需要改中文时用 `[System.IO.File]::WriteAllText($p,$c,
  (New-Object System.Text.UTF8Encoding $false))` 配 `[char]0xXXXX` 构造，可靠
- `Test-Path` 无法处理含中文的路径（见 `img/` 下 6 个 PNG）；改用
  `[System.IO.File]::Exists()`
- 本仓库基准为 JDK 17，`Matcher.region(int,int)` 可用，JDK 20 的
  `matcher(CharSequence,int,int)` 重载不可用
- `www.nhc.gov.cn` 对程序化请求返回 HTTP 412，须人工核对
- `backend/target` 是共享目录，并行跑 `mvn test` 会互相覆盖 surefire 报告；
  知识源实例已声明由它跑离线检查
- `git add` 只用具体路径，**禁止 `-A` / `add .`**（本仓库常有多方未提交改动）

## 6. 资源归属提醒

`tools/knowledge-sync/**`、`docs/KNOWLEDGE_*.md`、
`docs/BUSINESS_KNOWLEDGE_INVENTORY.md`、`docs/RULE_SOURCE_WORKLIST.md` 属另一
opencode 实例。若需要改动其中文件（如登记 `nhs-poisoning` / `who-suicide`），
请交由该 owner，不要直接修改。

## 7. GPT 接手后的进展（2026-10-02）

- `FOOD_REACTION`、`GENERALIZED_RASH`、`UNCLEAR_BLEEDING_RULE` 均改为从同一份
  `auxiliaryPatterns` 加载；声明名与消费名不一致时启动失败，测试钉住三项与模板展开。
- 将「吃了海鲜」与食物接触后的「红点」纳入既有食物反应模式；与全身红点组合时沿用
  `ER-ALLERGY-001` 的原有保守拦截，不新增严重度。此中文映射未经临床审核，不能
  据此声称已识别过敏性休克。否定、既往、局部红点有负向回归。
- JUnit 复验「嘴唇肿了，无法吞咽」为 `EMERGENCY / ER-AIRWAY-001`，因此此前
  jshell 探针所称「零信号」不成立；并未据此新增气道组合规则。无皮疹的急性肿胀
  或呼吸异常覆盖仍需独立临床审查，不能由这条测试推论为已覆盖。
- 规则版本升至 `CN-ADULT-ONLINE-TRIAGE-2026.10-P7`。JDK 17 在隔离构建副本
  `mvn clean test` 结果 90/90 通过；原 `backend/target` 被运行服务锁定，原路径
  的 clean/test 无法完成。前端原路径构建受临时配置文件写入权限阻断；隔离副本
  `npm run build` 已通过。以上验证不代表运行中的旧后端进程已加载新规则。

## 8. 独立的气道组合修正（P8，待独立复核）

- 先用 JUnit 在 P7 复现：「嘴唇肿了，无法吞咽」已命中
  `ER-AIRWAY-001 / EMERGENCY`；「吃了海鲜，嘴唇肿了，呼吸有点急」仍为
  `ROUTINE`。故逗号导致「无法吞咽」零信号的旧推断被证伪；真正缺口是食物接触
  + 唇肿 + 呼吸变急三项组合。
- P8 将唇肿与呼吸变急两个辅助模式声明在规则数据文件中。三项均肯定且未检出
  明确第三人主语时，复用既有 `ER-ALLERGY-001` 急症闸门；单独「呼吸有点急」
  不升级。新增否定、既往、第三人负向测试。NHS Anaphylaxis 是公开出处，但
  中文短语组合与主体词表仍未经临床审核，`citationGap` 保留；完整主体/时间归属
  仍属 Stage 2 未完成项。`nhs-anaphylaxis` 尚待知识源清单 owner 登记。
- JDK 17 隔离副本 `mvn clean test`：93/93 通过。前端未改动，P7 时的隔离
  `npm run build` 已通过。运行中的后端仍是旧进程，未经重启与端到端复验。
