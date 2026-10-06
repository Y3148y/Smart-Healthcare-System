# RAG 设计与验证

患者流程由后端显式调用检索工具，模型没有自主选择工具的循环。

## 输入与索引

内置 knowledge/*.md 是 11 篇手工整理的短资料，包含标题、来源和正文，不是来源网页完整快照。回答时不会访问来源 URL 抓取正文。资料不足是当前回答覆盖的已知限制。

正文和当前审批状态由 MyBatis 存入配置数据库，空表首次使用内置资料播种；已有记录不被启动种子覆盖。管理员新增资料先持久化，审批写入成功后才进入检索缓存和向量同步。数据库失败不允许只在内存中显示新增成功或发布待审核内容。重启后按存储记录重建片段与词法缓存。当前数据库验证使用 H2，尚未验证真实 MySQL；不可变版本与审核历史仍待实现。

正文按段落和 Markdown 标题切分，标题作为章节路径保存；代码围栏内的标题符号不改变章节。长段最多 420 个 Unicode 码点、重叠 60 个码点，优先在足够长的句末切开。片段记录文档ID、内容指纹、稳定片段ID、章节、原始正文码点位置与片段哈希；管理员 GET /api/admin/knowledge/{id}/chunks 可查看溯源预览，预览不证明向量同步完成。现有短语料无法充分验证真实长医学正文质量，条件和结论完整性仍需审核。片段用于进程内 BM25 统计索引和 embedding/Qdrant；新 collection 的 HNSW 参数可配置，小数据集可能走全扫描。

## 结构化入库契约

切分策略 v3 在同一章节内按原文顺序将相邻短段落打包到 420 码点以内，保留中间原文换行，避免每条列表独立成为极短片段；不跨标题拼接，不合成条件或结论。长段仍使用有界窗口，这不能保证所有长医学条件完整，超窗上下文和适用范围仍需审核。策略版本进入片段ID和向量payload，因此升级后须重新同步批准语料索引；待审批正文不会因此发布。

管理员页面使用结构化入库接口，支持读取本地 UTF-8 TXT/Markdown 正文，并要求填写来源标识、发布机构、链接和实际许可依据。录入后仍为待审批；详情展示来源声明、未声明项以及逐片段版本/位置/哈希。旧上传接口保留兼容，但新页面不再用它绕过来源录入。

来源工具的 intake 命令生成本地待审 JSON（reports/intake，不提交第三方全文）。管理员可读取该包：先校验抽取正文 SHA-256，再保留原始快照哈希/采集时间和清洗告警；语言和证据用途必须核对。快照哈希并不表示浏览器已核验原始网页实物。许可待核对或受限的资料可保存待审，但后端禁止审批发布；已许可状态仍需填写依据、确认并另行审批。工具输出不自动翻译、不填造适用范围、不为检索凑词。

管理员可通过 POST /api/admin/knowledge/documents 提交 title、body、metadata；正文保存到数据库，不在问答时访问来源网页。metadata.schemaVersion=1，包含 language、contentKind（source_extract/reviewed_summary）、sources（sourceId、publisher、url、可选 fetchedAt/rawSha256）、topics、population、exclusions、prerequisites、evidenceUses，以及 permissionStatus/permissionEvidence。evidenceUses 可选 general_information、direction_reference、warning_reference。

新结构化资料一律先进入 PENDING_REVIEW；permissionStatus=pending/restricted 时禁止审批发布，permitted 必须填写许可依据，仍需管理员另行审批。许可字段只是操作者声明，不是法律或临床认证，也尚未作为在线适用性过滤条件。GET /api/admin/knowledge/{id}/metadata 返回声明；旧资料没有这些字段时返回 LEGACY_UNKNOWN，不生成虚假来源或审核记录。原始网页快照仍由来源同步工具管理，声明哈希目前未与快照实物自动校验。

入库请求模板（示意字段，不是医学语料；缺失来源时间与哈希用 null，不编造）：

```json
{
  "title": "资料标题",
  "body": "# 适用范围\n\n整理后的正文，保留限定条件。\n\n# 就医提醒\n\n来源明确支持的内容。",
  "metadata": {
    "schemaVersion": 1,
    "language": "zh-CN",
    "contentKind": "source_extract",
    "sources": [{"sourceId": "source-id", "publisher": "发布机构", "url": "https://example.invalid/source", "fetchedAt": null, "rawSha256": null}],
    "topics": [], "population": [], "exclusions": [], "prerequisites": [],
    "evidenceUses": ["general_information"],
    "permissionStatus": "pending", "permissionEvidence": null
  }
}
```

## 在线检索

待审批资料可通过 ADMIN PUT /api/admin/knowledge/{id}/metadata 或详情中的补正表单修改来源与许可声明，正文不变且仍需审批。已审批资料拒绝该操作，不能借补正接口覆盖已发布证据。审批事务先锁定文档，再读取许可声明；单进程目录同步串行化补正与审批。跨进程缓存通知、完整修改历史与不可变版本仍未实现。页面修改首个来源时清空其旧采集时间和快照哈希，其他来源保留。

目录同步向 point payload 写入片段溯源（文档ID/版本、片段ID、章节、位置、正文哈希与切分策略）和 embedding 模型名；在线检索与离线构建使用同一目录同步入口。同一证据文本存在于多份资料时保留多个来源片段，不凭文本臆造唯一文档。旧 point 缺少这些字段时重新 upsert；完整 payload 相同时可在重启后复用。搜索只接受请求快照的 point id 和一致 payload；这是一致性校验，不是医学相关性证明。直接调用底层 Evidence 适配器时仍允许没有溯源的兼容模式。

查询分别进入 BM25 与 Qdrant，候选经 RRF 融合，再由配置的百炼 rerank 重排。返回片段作为模型依据；相关度分数不是医学可信概率。默认配置允许标识清楚的本地降级，rag-live 要求向量与重排依赖可用。

管理员检索诊断的每个词法候选包含 lexicalTerms：实际命中的词、片段集合文档频率、词频、IDF 与得分贡献；总和对应 BM25 得分。诊断不新增医学词表、不改相关度阈值，也不代表命中内容能回答问题。例如头晕资料警示句中的“剧烈头痛”可使“头痛”查询命中，但这不证明一般头痛的知识覆盖。评估报告保留此分解，患者端不展示内部评分。

安全评估独立于检索。检索命中不能覆盖风险限制，也不能证明片段适用于患者或生成内容受到支持。

## 可复现验证

`tools/rag-eval` 提供独立的原8081真实检索评估入口：`npm test` 验证评估器，`npm run evaluate` 调用本机管理员检索接口（默认演示admin登录；可用RAG_EVAL_USERNAME/PASSWORD进程变量指定账号）。固定16条非临床主题标签；记录批准片段ID/文档版本/切分策略/正文哈希、模型名、各路候选和选择结果，前后语料指纹不一致或任一期望/依赖失败时退出非零。报告只保存在被忽略的 `.codex-rag-evaluation/topic-baseline.json`，不包含令牌、片段正文或患者会话。接口失败退出2，不冒充“空结果通过”。标签只针对当前批准种子库；资料发布或撤回后须复核标签，不拿它代替临床标注集。

2026-10-06原8081/v3切分复验：Qdrant批准溯源11/11相符，待审全文未进入向量库；真实16条评估15项满足预期、14/14已有主题命中，“头痛”选中头晕资料使评估退出1。7条有额外选择需人工相关性复核。新增NHS偏头痛全文仍待审批，不能算在线覆盖；这些结果不证明生成答案合格。

普通 JDK 17 Maven 测试覆盖本地 BM25、融合、依赖失败、幂等索引与质量门禁，不调用真实模型。RagLiveIntegrationTest 需设置 AI_RAG_LIVE_TEST=true 与本机服务凭据；冻结测试数据在 backend/src/test/resources，报告生成到 target/rag-live*.json。

数据库失败回归覆盖新增回滚、审批不发布、元数据补正不改缓存；补正与审批的冲突测试使用H2事务锁，不等同于已验证MySQL或多实例部署。

KnowledgeIndexBuildRunner 通过 ai.knowledge.offline-index.enabled 显式启用，输出索引构建清单，包含语料哈希、分块策略、模型及实际 collection 参数。它证明构建输入和结果可追溯，不代表医学审核。

实际离线构建输出 schemaVersion=3 的清单，逐片段列出文档/版本/章节/位置/哈希与切分策略，不重复保存正文或密钥。批准语料与溯源数量不一致或ID重复时拒绝输出清单；旧 Evidence-only 工程评估调用保留 schemaVersion=2 的兼容清单。仅有 Markdown 标题、没有可检索正文的资料不允许入库。

## 尚未完成

来源登记以 tools/knowledge-sync/sources.json 为准。来源同步工具抓取快照、记录哈希和生成更新提案，不自动批准或发布患者语料。网页公开可访问不等于允许任意复制；完整正文入库前仍需核对许可、适用范围与日期。原始来源、整理正文和检索片段应可追溯。

源工具在网络访问前阻断声明为 restricted 的来源；targetRag=false 不生成患者待审包。已纠正 MedlinePlus 历史登记：003049 是第三方成人鼻塞/流鼻涕百科，不是公有领域普通感冒资料，未经授权禁止用于RAG。官方域名不能代替逐页许可核验。新增 NHS 偏头痛来源仅表示官方页面已定位，仍待核校与许可审批，不代表已进入在线知识库。

完整医学正文采集与许可核对、清洗去重、规范适用性元数据、不可变语料版本、审核历史、索引原子发布和回滚仍需补齐。工程评估集较小且未经临床审核，不能据此宣称可直接用于医院上线。
