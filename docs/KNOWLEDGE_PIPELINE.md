# 知识语料管线：怎么加一条知识，为什么不能直接爬

> **性质**：本文描述原型工程链路，**不构成临床认可**。语料内全部内容未经临床审核。
> 配套：[`docs/KNOWLEDGE_SOURCES.md`](KNOWLEDGE_SOURCES.md)（收录标准与来源）、[`tools/knowledge-sync/README.md`](../tools/knowledge-sync/README.md)（命令）

---

## 1. 一句话结论

**抓取只用来发现「出处变了」，不用来生产语料。** 语料必须人工改写、经批准、单独提交。

## 2. 为什么不能自动入库

三个理由，任何一个都足以否决自动入库：

1. **仓库自己的政策**：`docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md:42` ——「不复制整篇网页；将与导诊直接相关的信息改写为短知识片段，保留原始 URL」。
2. **版权**：NICE、SDCEP、NHS 的内容都不是本项目可以随便再发布的。整页搬运既是法律问题，也会让仓库被当成内容聚合站。
3. **临床安全**：自动抽取的正文无法保证「不含诊断/处方/药名」，而本系统明确拒答诊断与处方（`AGENTS.md` 第 5 条、《互联网诊疗监管细则（试行）》明令禁止 AI 自动生成处方）。未经改写的原文进到患者眼前，等于绕过了我们自己设的那道闸。

## 3. 实际链路

```
sources.json（登记的官方来源）
      │  npm run fetch        robots 检查 → 条件 GET → 快照落 cache/（gitignore）→ sha256 进 state.json
      ▼
reports/sync-<date>.md       状态 / 哈希 / 首次差异位置（不复制原文）
      │  npm run proposals
      ▼
reports/proposals/<id>.md    改写工作单：出处头 + 检查清单 + 空白待填正文 + 抽取正文（仅供阅读）
      │  人工或有判断力的 AI 改写
      ▼
backend/src/main/resources/knowledge/<序号>-<主题>.md      ← 唯一真正的发布动作
      │  npm run verify   （离线复算，先看召回会不会翻转）
      │  mvn test（JDK 17） / frontend npm run build
      ▼
owner 批准 → 独立 commit
```

## 4. 发布一条新语料前必须知道的四件事

### 4.1 新文件立刻对患者可见，没有灰度

`InMemoryKnowledgeCatalog.java:73-84`：内置 md 在启动时同步加载，`来源：` 以 `https://` 开头就直接置为 `READY` 并建索引——**没有审核队列**。所以语料 commit 一旦合并，下次启动就上线。管理员上传走 `PENDING_REVIEW` 的那套只是旁路，内置文件不经过它。

因此：**语料 commit 必须先经过复核**，不能先合了再补审。

### 4.2 行首格式是硬约定

解析器（`InMemoryKnowledgeCatalog.java:161-176`）只认四种行首，其余行一律拼成正文：

```
# 标题                    ← 必须是 "# " 加空格；"## " 不是标题行，会留在正文里
来源：https://…            ← 全角冒号；必须 https:// 开头，否则文档不可见
补充来源：https://…        ← 可重复，多个来源会用 " | " 连接
主题：词1、词2             ← 全角冒号；会被前置到正文参与检索
```

### 4.3 词表只有 52 个词

`InMemoryKnowledgeCatalog.java:34-40` 是一份写死的 52 词中文词表。评分是
`min(0.99, 词项命中比例 × 0.62 + 字符 bigram 余弦 × 0.30 + 权威 0.08/0.02)`（`:110`）。

- 查询词全在词表内且片段命中 → 词项分拉满，这一条就贡献 0.62。
- **片段用词在词表外 → 词项分贡献为 0**，只剩 bigram 的 0.30 加权威 0.08，上限约 0.38，而阈值是 0.28。也就是说词表外的词只能靠余弦勉强够线。

现状：`npm run verify` 显示 11 篇里有 6 篇的主题含词表外术语（`严重呼吸困难`、`心肌梗死`、`面瘫`、`ABCDE`、`气道`、`外伤`、`普通预问诊` 等）。**扩词表是独立任务，不在本管线范围内**；在改词表之前，写新语料要尽量复用既有 52 词。

### 4.4 阈值是按 12 个片段标定的

`docs/QDRANT_LIVE_VERIFICATION_2026-09-30.md:54` 明确写着「阈值 0.28/0.45 是经验值，换语料或换embedding 模型需重新标定」。当前语料只有 11 篇 / 12 片段，且最长段落 227 字，**远未触及 `CHUNK_SIZE=420` 的滑窗路径**。片段数量上一个量级后，分布会变，0.28 是否还合适必须重新用 `verify` 与实际召回观察，不要假设它继续成立。

## 5. verify 在提交前挡住什么

`npm run verify` 复算生产算法，检查：

- 文件数 / 可检索片段数（基线 11 / 12）
- 三条已被 Java 测试锁定的断言：`流鼻涕` top1 标题、`咳嗽胸闷挂什么科` top1 来源须为 https、`头晕` top1 标题
- 每个文档是否真的 READY
- 段落是否超 420 字（会切片并产生重叠片段）
- 主题里有多少词落在 52 词表之外
- top1/top2 是否分数并列

## 6. 已知的检索缺陷（读码所得，未修）

| 缺陷 | 位置 | 后果 |
| --- | --- | --- |
| 同分片段顺序不确定 | `InMemoryKnowledgeCatalog.java:91` 从 `ConcurrentHashMap.values()` 取流 | 两个片段分数持平时，top-1 可能在不同运行间翻转。`verify` 会在分数差 < 0.001 时告警 |
| Qdrant 点无稳定身份 | `QdrantSemanticIndex.java:69-71` 点 id 由 `title + "|" + excerpt` 派生 | 语料内容改写后旧点成孤儿，缓存集合 `indexedPointIds`（`:36`）只增不减，需重建 collection |
| `runtime.mode` 不实时 | `HybridKnowledgeCatalog.java:33` 读的是锁存布尔值 | 索引成功后 Qdrant 掉线，界面仍显示 `HYBRID_QDRANT` |
| 管理员新增文档不持久 | 无知识相关数据表（`schema.sql` 只有 `sim_*` 与 `triage_*`） | 重启即丢；`KnowledgeDocument`（`Models.java:27`）也没有来源 URL、版本、许可、审核状态字段 |
| 索引在请求路径上同步执行 | `HybridKnowledgeCatalog.java:37` 每次检索都调 `ensureIndexed` | 冷启动首个查询会同步做全部 embedding，超时 15 秒（`QdrantSemanticIndex.java:119`）；语料变大后这段会变成分钟级阻塞 |
| 文档正文截断到 4000 字 | `OptionalNarrationModel.java` / `RuleBasedTriageEngine` 的证据拼接 | 片段变多后，送进模型的证据会被截断，需要排序策略 |

## 7. 复核清单（提交语料前）

- [ ] `npm run verify` 通过，且三条被锁定断言仍 PASS
- [ ] 新文件 `来源：` 是 `https://` 开头
- [ ] 每段落 ≤ 400 字，危险信号段落在最后
- [ ] 正文写明「未经临床审核」
- [ ] 不含药名、剂量、诊断结论、治疗方案
- [ ] 主题尽量用 52 词表内术语（`verify` 会报出词表外词）
- [ ] JDK 17 下 `mvn test` 全绿（无任何 `AI_*` 环境变量）
- [ ] `frontend npm run build` 通过
- [ ] `docs/RAG_KNOWLEDGE_CATALOG_2026-09-26.md` 的语料表已同步（AGENTS.md 第 3 条：文档是单一事实源）
- [ ] 新发现的检索缺陷已登记；不要在语料 commit 里顺手改生产代码

---

**本文档不构成临床认可。** 所列规则覆盖与改动方向均需具备资质的临床人员审核后方可用于真实患者场景。