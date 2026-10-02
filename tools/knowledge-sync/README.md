# knowledge-sync

知识源抓取与变更留痕工具。**它永远不会往 `backend/src/main/resources/knowledge/` 写任何东西。**

## 为什么需要它

仓库里的 RAG 语料是手工改写的短段落（`docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md:42` 明确「不复制整篇网页」）。手工改写有两个问题：出处页面被悄悄更新时没人知道；新语料的召回效果只能靠启动应用试出来。这个工具解决这两件事，不参与发布。

## 命令

| 命令 | 联网 | 作用 |
| --- | --- | --- |
| `npm run sources` | 否 | 打印登记表摘要（优先级、抓取方式、核实状态） |
| `npm run refs -- <commit>` | 否 | 校验两份文档引用的代码位置是否仍成立（`doc-refs.json` 里 28 条"该行必须包含的内容串"） |
| `npm run citations` | 否 | 校验规则数据文件里每个 citation id 都能在 `sources.json` 找到，并盯住未核实 / `manual` / 自述占位的来源。**只读，不改规则文件** |
| `npm run verify` | 否 | 离线复算生产检索算法，预测语料改动的召回影响 |
| `npm run fetch -- --dry-run` | 是 | 抓取并打印结果，不写 `state.json` |
| `npm run fetch` | 是 | 抓取到期来源，写快照到 `cache/`、哈希到 `state.json` |
| `npm run fetch -- <id> [<id>]` | 是 | 只抓指定来源 |
| `npm run fetch -- --cycle` | 是 | 只抓超过各自复核周期的来源 |
| `npm run diff` | 否 | 生成差异报告到 `reports/`（只报位置与长度） |
| `npm run proposals` | 否 | 为 new/updated 来源生成改写工作单 |

## 边界

- **只读不写语料**：入库必须是独立、经 owner 批准的手工 commit。
- **只登记官方来源**：官方机构或公立医院公开资料。商业内容、论坛问答、征求意见稿、地方文件不进 RAG。
- **robots.txt 优先**：读不到就不抓，不伪装 UA 绕过 WAF。
- **条件 GET**：带 `ETag` / `Last-Modified`，未变更返回 304，不浪费对方带宽。
- **限速**：同 host 至少 5 秒一次；传输失败只重试一次。
- **原文不入库**：`cache/` 与 `reports/` 已在 `.gitignore`；git 内只有 URL、时间、sha256、状态与许可提示。

## verify 复算的是什么

`lib/verify.mjs` 是 `InMemoryKnowledgeCatalog` 的 1:1 移植（分块 420/60、评分 0.62 词项 + 0.30 bigram + 0.08/0.02 权威、52 词词表、`来源：` 必须 https 才可见）。它跑三组已被 Java 测试锁定的断言，外加语料契约检查与一个词表外守卫：

- 文件数与可检索片段数（当前基线 11 / 12）
- 每个文档是否真的 READY（来源非 `https://` 开头 → 患者永远看不到）
- 段落是否超 420 字（会被切片并产生重叠片段）
- 主题里有几个词落在 52 词词表之外（词表外词对词法得分贡献为 0）
- top1/top2 是否分数并列（并列时生产环境 top-1 可能翻转，见下）
- **词表外守卫**：14 条纯词表外查询（`脓毒症`/`发热`/`张口受限`/`意识模糊`…）必须仍全部 `grounded=false`。命中意味着阈值、语料或词表之一变了，需要显式裁定。守卫只覆盖词法路径——启用 embedding 后 `grounded` 可由向量余弦单独产生（`HybridKnowledgeCatalog.java:43`），本工具不验证该路径。

## 已知的坑

- `InMemoryKnowledgeCatalog.java:91` 从 `ConcurrentHashMap.values()` 取流，**同分片段顺序不确定**。verify 对分数差小于 0.001 的情况会显式告警。
- `www.nhc.gov.cn` 对程序化请求返回 **HTTP 412**（WAF）。相关来源标 `manual`，只能人工打开阅读。
- 零依赖抽取器只处理 HTML。PDF（NHS England 易读版）与 .docx（国卫医发〔2018〕25号 附件、NMPA 通告附件）只能人工阅读。
- HTML → 文本是粗筛：导航残留可能混进抽取正文。工作单里已标注「未经核校」，必须人工确认。

## 依赖

Node ≥ 20（用到全局 `fetch`）。**零第三方依赖**，不需要 `npm install`，不需要任何 API 密钥。