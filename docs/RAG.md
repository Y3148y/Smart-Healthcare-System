# RAG 设计与验证

患者流程由后端显式调用检索工具，模型没有自主选择工具的循环。

## 输入与索引

内置 knowledge/*.md 是 11 篇手工整理的短资料，包含标题、来源和正文，不是来源网页完整快照。回答时不会访问来源 URL 抓取正文。资料不足是当前回答覆盖的已知限制。

正文和当前审批状态由 MyBatis 存入配置数据库，空表首次使用内置资料播种；已有记录不被启动种子覆盖。管理员新增资料先持久化，审批写入成功后才进入检索缓存和向量同步。数据库失败不允许只在内存中显示新增成功或发布待审核内容。重启后按存储记录重建片段与词法缓存。当前数据库验证使用 H2，尚未验证真实 MySQL；不可变版本与审核历史仍待实现。

正文按段落和 Markdown 标题切分，标题作为章节路径保存；代码围栏内的标题符号不改变章节。长段最多 420 个 Unicode 码点、重叠 60 个码点，优先在足够长的句末切开。片段记录文档ID、内容指纹、稳定片段ID、章节、原始正文码点位置与片段哈希；管理员 GET /api/admin/knowledge/{id}/chunks 可查看溯源预览，预览不证明向量同步完成。现有短语料无法充分验证真实长医学正文质量，条件和结论完整性仍需审核。片段用于进程内 BM25 统计索引和 embedding/Qdrant；新 collection 的 HNSW 参数可配置，小数据集可能走全扫描。

## 结构化入库契约

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

目录同步向 point payload 写入片段溯源（文档ID/版本、片段ID、章节、位置、正文哈希与切分策略）和 embedding 模型名；在线检索与离线构建使用同一目录同步入口。同一证据文本存在于多份资料时保留多个来源片段，不凭文本臆造唯一文档。旧 point 缺少这些字段时重新 upsert；完整 payload 相同时可在重启后复用。搜索只接受请求快照的 point id 和一致 payload；这是一致性校验，不是医学相关性证明。直接调用底层 Evidence 适配器时仍允许没有溯源的兼容模式；离线清单尚未包含逐片段溯源信息。

查询分别进入 BM25 与 Qdrant，候选经 RRF 融合，再由配置的百炼 rerank 重排。返回片段作为模型依据；相关度分数不是医学可信概率。默认配置允许标识清楚的本地降级，rag-live 要求向量与重排依赖可用。

安全评估独立于检索。检索命中不能覆盖风险限制，也不能证明片段适用于患者或生成内容受到支持。

## 可复现验证

普通 JDK 17 Maven 测试覆盖本地 BM25、融合、依赖失败、幂等索引与质量门禁，不调用真实模型。RagLiveIntegrationTest 需设置 AI_RAG_LIVE_TEST=true 与本机服务凭据；冻结测试数据在 backend/src/test/resources，报告生成到 target/rag-live*.json。

KnowledgeIndexBuildRunner 通过 ai.knowledge.offline-index.enabled 显式启用，输出索引构建清单，包含语料哈希、分块策略、模型及实际 collection 参数。它证明构建输入和结果可追溯，不代表医学审核。

## 尚未完成

来源登记以 tools/knowledge-sync/sources.json 为准。来源同步工具抓取快照、记录哈希和生成更新提案，不自动批准或发布患者语料。网页公开可访问不等于允许任意复制；完整正文入库前仍需核对许可、适用范围与日期。原始来源、整理正文和检索片段应可追溯。

完整医学正文采集与许可核对、清洗去重、规范适用性元数据、不可变语料版本、审核历史、索引原子发布和回滚仍需补齐。工程评估集较小且未经临床审核，不能据此宣称可直接用于医院上线。
