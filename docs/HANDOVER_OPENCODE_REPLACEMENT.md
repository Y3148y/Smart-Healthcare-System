# 交接：opencode 主会话 → 接任 opencode

> 写入方注：本文件由被替换的会话生成。它无法可靠输出 U+5B3A（见第 1 节），
> 因此文中该字符一律以 [char]0x5B3A 标注，接手方需自行替换为正确字符。

## 0. 先读这三条

1. **你的工作目录很可能是错的。** 我被创建的目录是 D:\IDEAprojects\langchain4j（非 git 仓库），
   而全部工作实际在 D:\IDEAprojects\ai。我每次命令都显式指定目录，期间误在 langchain4j
   跑过一次 mvn clean test。**接手后请先确认自己的 cwd，或每条命令都显式带目录。**
2. **仓库存在一个我无法修复的错字问题**，见第 1 节。它阻塞一切中文编辑。
3. **ackend/target 是共享目录。** 与另两个实例并行跑 mvn test 会互相覆盖 surefire 报告。

## 1. 阻塞项：无法输出 U+5B3A 的字符

现象：我输出 [char]0x5B3A（嬺）这个字时，经我的消息通道与写文件路径后，落盘变成 U+5B55（孙，孙）。

证据（只读复核，可重现）：

- 全仓 U+5B3A = **0** 处；U+5B55 = **250** 处
- docs/AGENT_BOARD.md（我两轮前提交的）含 3 处，上下文是「嬺 产状态待确认」「嬺 产否定形式覆盖不全」
- ackend/src/main/resources/safety-rules.json 中 pregnancyState 实际码位为
  U+6000 U+5B55 | U+5B55 U+671F | ... U+6000 U+5B55，即「怀孙 | 孙期 | 可能怀孙」
- 但 PowerShell 自身正常：[char]0x5B3A 确实得到 U+5B3A。**故障在我的输出，不在 PowerShell 或写盘。**

后果（未实测复核，接手方请自行确认）：

- ER-PREGNANCY-001 / UR-PREGNANCY-001 可能从未匹配真实关键词
- TriagePage.vue 患者可见文案可能显示错字
- 相关测试若用同样错字书写，会自洽通过 —— 83/83 可能是假绿

**为何我不能自己修：** 修复必须写出正确字符，而我写不出。**这是接手方与 GPT 的活。**

修复时请**每改一处就读回验码位**，不要改完统一检查。

## 2. 仓库状态（只读复核过）

`
HEAD      2dddaa8  docs: 核实 sdcep 可达性、登记宫外孕来源
工作树    干净
未推送    4 个提交
基线      83/83 测试绿（JDK 17），前端构建通过
版本      POLICY_VERSION = CN-ADULT-ONLINE-TRIAGE-2026.10-P6
`

已落地（我的 commit，均已推送或待推）：

| 内容 | commit |
| --- | --- |
| D10 两步评估 + 状态待确认 + 文案 | 57e67bc 41d6562 e36cb25 cbd2008 |
| 合规拒答前端显示 | c150929 |
| D11 三条裁定（明显出血/句号/紧邻否定） | f43242 52a1bc cd397c2 |
| A2 规则数据化（新增 safety-rules.json） | f99335 384305 |

## 3. Owner 分工（已写入 docs/AGENT_BOARD.md）

| 范围 | Owner |
| --- | --- |
| 	riage/** ooking/** catalog/** observation/**、所有 *Test.java、rontend/src/features/triage/**、safety-rules.json、docs/HANDOVER_TRIAGE_SAFETY_2026-10-02.md、docs/KNOWN_ISSUES_PRECLINICAL.md、docs/D4_*.md、docs/AGENT_BOARD.md | **GPT（已由我移交）** |
| 	ools/knowledge-sync/**、docs/KNOWLEDGE_*.md、docs/BUSINESS_KNOWLEDGE_INVENTORY.md、docs/RULE_SOURCE_WORKLIST.md | 另一 opencode 实例 |
| 临床裁定（规则词表、严重度、出处措辞） | GPT |

**注意 docs/AGENT_BOARD.md 本身含第 1 节的错字（3 处）。它是我写的，也不可信。**

## 4. 待办（全部阻塞在第 1 节）

**先做：** 修 250 处错字，并复核孕产相关规则与测试是否真的生效。

**GPT 已裁定、待做：**

1. ER-POISON-001 补出处（
hs-poisoning、who-suicide）+ 反例测试，**不加词不删词**
2. UR-FEVER-001 数据口径改为「本演示规则的一种高热触发条件」，不得写成指南支持 ≥39℃
3. 过敏召回：FOOD_REACTION 症状词表缺「红点」（GENERALIZED_RASH 有），补该词，严重度不动
4. ER-AIRWAY-001 补气道/严重过敏组合（依据 
hs-anaphylaxis 999 判据），**单独提交**，
   且须先在当前运行版本复现「嘴唇肿+无法吞咽」是否真为零信号

**仍待裁定：**

- 第三人/既往/跨轮事件隔离（需主体与时间归属模型，Stage 2）
- ER-NEURO-001 引 
ice-ng253 属出处不匹配（晕厥的出处不是 NG253），建议补 
hs-fainting
- ER-AIRWAY-001 引了占位条目 
hc-er-dept-guideline 当正式出处
- grounded 在启用 embedding 后可由向量余弦单独产生（HybridKnowledgeCatalog），动摇 Q4 支点
- RuleBasedTriageEngine「第二十一条」条号：文号对，条号二手证据称第十三条，北京/上海为第十四/二十九条

## 5. 我犯过的错（请勿重蹈）

| 错误 | 根因 | 教训 |
| --- | --- | --- |
| 用 jshell 经 PowerShell 管道做中文探针，\uXXXX 转义未解析，探针匹配的是字面量 | 工具链误用 | **先证伪工具，再信数据。** 上报任何「实测」前，先用已知为真的输入验证测量手段 |
| 把「写入路径完好」的结论写进 AGENT_BOARD.md，把真 bug 描述成我的幻觉 | 基于坏探针翻转了正确结论 | 一旦翻转过一次，就要求更高的证据门槛 |
| 报告里给出「143 行 / 250 处」等具体数字 | 数字放大可信度 | 具体行数会让未核实的猜测看起来像已核实 |
| 在错误的工作目录下执行命令 | cwd 与实际仓库不一致 | 每条命令显式带目录 |

## 6. 环境事实

- 基准 JDK E:\JDK17\jdk-17.0.1，须先设 $env:JAVA_HOME；默认 java 是 1.8
- **不要用 JDK 25**（D:\Elasticsearch\elasticsearch-9.3.3\jdk）：Mockito 的 Byte Buddy 仅支持到 Java 23，mock 具体类会产生假失败
- mvn compile 增量编译可能读旧 target，改动后必须 mvn clean test
- PowerShell 5.1：> 重定向会破坏中文；控制台回显中文是乱码，**判断中文内容必须读码位，不要看控制台**
- www.nhc.gov.cn 对程序化请求返回 HTTP 412，须人工核对
- git add 只用具体路径，**禁止 -A / dd .**（本仓库常有多方未提交改动）