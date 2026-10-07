# knowledge-sync

知识源抓取与待审入库工具，不写应用resources。运行node import-local.mjs packet.json默认校验；显式--apply才创建本机后台待审资料，不自动审批。包须包含schemaVersion=1、title、body、contentSha256及metadata；后台ADMIN token仅通过KNOWLEDGE_ADMIN_TOKEN环境变量注入。超时后先检查后台，导入尚无服务端幂等键，不盲目重试。

## 为什么需要它

应用已移除旧内置短摘要；历史正文仅作测试夹具，不随应用发布。本工具记录来源变更与待审工作单，不自动发布；正文、来源元数据、许可和适用范围应经审核后通过知识管理入库。新库需显式入库，不能把本机私有语料视为自动附带。见docs/RAG.md。

## 命令

| 命令 | 联网 | 作用 |
| --- | --- | --- |
| `npm run sources` | 否 | 打印登记表摘要（优先级、抓取方式、核实状态） |
| `npm run refs -- <commit>` | 否 | 校验两份文档引用的代码位置是否仍成立（`doc-refs.json` 里 28 条"该行必须包含的内容串"） |
| `npm run citations` | 否 | 校验规则数据文件：① 每个 citation id 都能在 `sources.json` 找到，并盯住未核实 / `manual` / 自述占位的来源；② 五条结构不变量（零引用必须显式标 `citationGap`、`combinations` 的码必须存在、`{ref:…}` 必须可解析、规则码唯一、派生规则须声明 `condition`）。**只读，不改规则文件** |

历史引用ID可通过sources条目的aliases对应到同一个真实来源，不复制登记与抓取记录；ID或别名冲突直接报错。别名不会隐藏未核实、占位或手动复核告警。目前国家急诊科指南占位引用仍未解决，citations不能宣称全通过。
| `npm test` | 否 | 抓取工具回归测试 |
| `npm run verify` | 否 | 旧词法检索参考检查，不代表当前在线混合检索评估 |
| `npm run fetch -- --dry-run` | 是 | 抓取并打印结果，不写 `state.json` |
| `npm run fetch` | 是 | 抓取到期来源，写快照到 `cache/`、哈希到 `state.json` |
| `npm run fetch -- <id> [<id>]` | 是 | 只抓指定来源 |
| `npm run fetch -- --cycle` | 是 | 只抓超过各自复核周期的来源 |
| `npm run diff` | 否 | 生成差异报告到 `reports/`（只报位置与长度） |
| `npm run proposals` | 否 | 为 new/updated 来源生成改写工作单 |
| `npm run intake` | 否 | 从成功快照生成正文＋来源元数据的本地待审 JSON；校验原始哈希，不联网、不发布 |

`reports/intake/` 含完整抽取正文及清洗告警，受 gitignore 排除，不推公开仓库。许可始终 pending，登记表的许可提示不能自动升级为 permitted；语言、主题、人群、用途等留待核校，不由脚本猜测。来源缺快照、抓取失败或哈希不符会登记 BLOCKED。审核后才提交管理员导入，审批与索引由后端负责。

## 边界

- **只读不写语料**：入库必须是独立、经 owner 批准的手工 commit。
- **只登记官方来源**：官方机构或公立医院公开资料。商业内容、论坛问答、征求意见稿、地方文件不进 RAG。
- **逐页核对权利**：官方域名可能托管第三方受限内容，不等于公有领域。permissionStatus=restricted 在网络请求前阻断；targetRag=false 不生成患者待审包。MedlinePlus 的 A.D.A.M. 百科来源已停用，保留历史ID便于审计。
- **robots.txt 优先**：每次工具进程首次访问来源先重新读取；网络失败、非成功响应或跳转均停止抓取（`robots-unavailable`）。这是本工具的保守策略，不宣称完整协议实现。支持分组、最长路径匹配、同长度 Allow 优先、通配符和末尾锚定。旧磁盘空缓存不作为许可。
- **不自动跟随跳转**：页面或 robots 跳转需先核对并重新登记最终来源，不绕过目标站点的抓取要求。
- **条件 GET**：带 `ETag` / `Last-Modified`，未变更返回 304，不浪费对方带宽。
- **限速**：同 host 至少 5 秒一次；传输失败只重试一次。
- **原文不入库**：`cache/` 与 `reports/` 已在 `.gitignore`；git 内只有 URL、时间、sha256、状态与许可提示。

## verify 复算的是什么

`lib/verify.mjs` 保留了旧词法算法（0.62 词项 + 0.30 bigram + 0.08/0.02 权威、52 词词表）的参考检查。它不是当前 BM25、embedding/Qdrant、RRF 与 rerank 链路的复算，也不验证新 Markdown 切分与适用范围。以下是旧检查项，不能作为当前链路的验收结论：

- 文件数与可检索片段数（当前基线 11 / 12）
- 每个文档是否真的 READY（来源非 `https://` 开头 → 患者永远看不到）
- 段落是否超 420 字（会被切片并产生重叠片段）
- 主题里有几个词落在 52 词词表之外（词表外词对词法得分贡献为 0）
- top1/top2 是否分数并列（并列时生产环境 top-1 可能翻转，见下）
- **词表外守卫**：14 条纯词表外查询（`脓毒症`/`发热`/`张口受限`/`意识模糊`…）必须仍全部 `grounded=false`。命中意味着阈值、语料或词表之一变了，需要显式裁定。守卫只覆盖词法路径——启用 embedding 后 `grounded` 可由向量余弦单独产生（`HybridKnowledgeCatalog.java:43`），本工具不验证该路径。

## 已知的坑

- `InMemoryKnowledgeCatalog.java:91` 从 `ConcurrentHashMap.values()` 取流，**同分片段顺序不确定**。verify 对分数差小于 0.001 的情况会显式告警。
- `www.nhc.gov.cn` 对程序化请求返回 **HTTP 412**（WAF）。相关来源标 `manual`，只能人工打开阅读。
- 抽取器只处理 HTML；PDF 与 .docx 尚需单独处理，不当作成功抽取。
- HTML 使用锁定版本 parse5 解析，保留标题、段落、列表、表格行及复核日期。优先 main/role=main，再 article，最后 body；多区域、body 回退及表格都标记待复核。不是临床核校，也不能保证复杂页面没有导航残留。
- 工作单不再要求按固定词表凑词、压成单块或删改原始证据。发布须提交结构化元数据并审核许可、用途和适用范围。
- **抓取失败先看 httpStatus 再下结论**：`403`/`412` 是站点侧拦截（WAF），`unreachable`（connect timeout）是网络侧。`sdcep-spreading-infection` 曾连续两次 `UND_ERR_CONNECT_TIMEOUT`，单次重试即成功——**不可达不等于被墙**。
- **行号断言不适合 JSON 数据文件**。在真实 `safety-rules.json` 上实测：`citations` 一词出现 17 次，按惯例选中的第 9 行其实是 `unreviewedDefault` 的说明文字，一条「第 9 行含 citations」的断言**当场假通过**；顶层插入任何字段后它又变成指向 `},` 的噪声 FAIL，而 JSON 语义毫无变化。该文件因此走 `citations` 的语义校验，不进 `doc-refs.json`。

## 依赖

Node ≥ 20（全局 `fetch`）；`npm ci` 安装锁文件固定的 parse5 HTML 解析器，不需要 API 密钥。解析方式见 [parse5](https://github.com/inikulin/parse5)。
# 内置语料盘点

`npm run audit:corpus`只读盘点11份旧Markdown：来源声明、正文码点数、内容哈希及缺失声明。不会自动批准、联网、复制正文或修改数据库。500码点仅为短资料人工复核提示，不是临床质量判定。新入库资料的审核仍走既有intake与管理员审批流程。
