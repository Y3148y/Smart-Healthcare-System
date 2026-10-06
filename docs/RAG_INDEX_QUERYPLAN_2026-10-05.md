# RAG 索引复用与影子查询规划

Owner：GPT；属于原改造方案C的部分实施，不代表C完成。

## 2026-10-05 RAG工程化成熟度盘点（代码与产物核对）

结论：当前已经有在线混合检索及若干离线守卫/集成测试，但离“离线可复现构建 + 在线稳定服务 + 有标注、有门槛的质量评估闭环”还有明显缺口。应定位为工程化中的原型，不是已完成的医院上线 RAG。此处“离线”指语料处理、切分、向量/词法索引构建；“评估”指独立于单元测试的检索质量测量。

| 阶段 | 已实现并可验证 | 缺口 / 边界 | 判断 |
| --- | --- | --- | --- |
| 离线语料与索引 | 仓库内 11 篇手工整理 Markdown；按段落/标题形成运行片段；Qdrant 有 12 个点且与运行片段 payload 逐项精确匹配 12/12。knowledge-sync 可抓取来源快照、哈希、差异和改写提案；不会自动发布语料。新增 opt-in `KnowledgeIndexBuildRunner` 可重复触发当前已批准语料的 embedding/upsert，并原子输出不含正文/密钥的稳定指纹清单。 | 抓取抽取是 HTML 粗清洗，可能含导航残留；没有自动正文清洗/去重/切分质量验收/医学审校流水线。语料审批与目录当前在内存。没有语料版本库、索引蓝绿发布/别名切换及回滚。BM25 索引是进程内快照，不落盘。当前片段 82–227 字，420 字/60 字重叠的长片段路径未被现有线上语料触发。 | 部分具备；有可重复构建入口，但尚非受版本与发布治理的离线流水线。 |
| 在线检索服务 | 患者请求由 Java 工作流显式调用本地检索执行器；活动链路是 BM25 + Qdrant 向量召回 → RRF → 百炼 rerank。required 依赖失败时可阻断，避免把依赖故障伪装成无资料。Qdrant collection 当前 Cosine/1024、配置有 HNSW；12 个点低于 `full_scan_threshold=10000`，实际由全扫描处理。 | 资料/审批不是持久化发布制；向量点没有独立 chunk/version 标识，当前对照正确不代表版本治理已完成。进程内检索事件最近最多 100 条且主要记模式、状态、候选数、耗时，不能构成完整患者逐轮 Trace，也不是长期审计日志。患者回答的采用依据另有有限快照，但不等于完整链路追踪。 | 在线功能存在，运行观测与版本运营仍是演示/原型程度。 |
| 评估与回归 | BM25、RRF、Qdrant upsert、rerank 失败关闭等组件单测覆盖。离线冻结开发集 12 条（11 条有答案）已对 legacy/BM25 对比；本轮将 BM25 的 Recall@3≥10/11、禁止资料=0、库外误召回=0 设为本地门禁。`RagLiveIntegrationTest` 有 12 条开发与 12 条 holdout 合成查询，可显式调用真实 embedding/Qdrant/rerank 并计算 Recall@3、MRR、forbidden hit，结果写 `backend/target/rag-live.json`。 | 实时集成测试需 `AI_RAG_LIVE_TEST=true` 与真实服务/密钥，普通 `mvn test` 默认跳过。2026-10-03 已有真实百炼报告：开发集 11/11 有答案查询首位命中，留出集 8/8 首位命中，禁止资料与库外误召回均为 0；本轮把这些已测基线接入 live 门禁。样本是工程主题标注，不是独立临床审核集；16 条主题基线也不是临床 gold set。 | 有本地 BM25 回归门禁及 live 混合检索质量门禁；仍缺独立内容审核、更大样本与临床验证。 |

### 本轮执行的可复验检查

- `tools/knowledge-sync` 的 `npm run verify` 成功：语料契约 11 篇/12 个片段，3 条 pinned 词法断言、14 条词表外查询守卫均通过。它实质镜像旧 `InMemoryKnowledgeCatalog` 的词项/字符 bigram/权威分数，不包含活动链路的 BM25、Qdrant、RRF 或百炼 rerank；因此不是当前线上混合 RAG 的离线回放或质量评估。`tools/knowledge-sync/**` 属另一 owner，本轮未改。其 README 将此命令描述为生产检索算法 1:1 回放，与活动代码不一致，应由该范围 owner 修正文案或实现真正的 replay。
- `RagLiveIntegrationTest` 存在两份各 12 条数据。历史报告 [rag-qwen-development-2026-10-03.json](evaluations/rag-qwen-development-2026-10-03.json) 与 [rag-qwen-holdout-2026-10-03.json](evaluations/rag-qwen-holdout-2026-10-03.json) 显示其各自有答案样本均首位命中，禁止资料与库外误召回均为 0；`docs/RAG_REDESIGN_2026-10-02.md` 记录了该 holdout 是额外冻结保留集。此前自动化仅断言走 HYBRID mode，没有把这份已测基线设为回归门槛。本轮新增门禁：每条有答案样本 Recall@3=1 且首位命中；禁止资料命中数及无答案误召回数必须为 0。实时逐条证据仍写 `target/rag-live.json`，新增汇总指标写 `target/rag-live-summary.json`。该门禁只锁当前小型工程样本的已知基线，不是医学质量标准。此轮只运行本地测试，不调用真实模型/embedding，也不扣额度。
- `RagRelevanceBaselineTest` 的真实本地 BM25 冻结查询重放也新增门禁：11 条有答案样本中至少 10 条于 top-3 命中（对应 2026-10-03 已测 10/11 基线），禁止资料命中与唯一库外问题误召回均为 0；历史与 BM25 结果仍写到测试 target 报告。此门禁对本地真实 Java BM25 执行，不调用模型或外部服务。
- 新增门禁正反单测后，用 JDK 17 在隔离输出目录执行后端完整 `mvn test -q -DforkCount=0`：249 项，248 通过、1 个 opt-in 外部服务测试跳过、0 失败/错误；前端 `npm run build` 通过。执行时没有 `AI_*` 环境变量，所以 live 指标门禁本身只由单测验证其 pass/fail 判定，未再次调用百炼/Qdrant。`git diff --check` 通过。
- 在线 12/12 payload 对照只证明当前这批 point 与运行片段一致；当前 12 点规模下 HNSW 参数不是检索质量异常的已证实根因。

### 判断与补齐顺序

所以对“离线阶段、在线阶段和评估阶段做了没有”的准确回答是：在线链路已有可运行实现；离线具备来源留痕、代码级切分和可重复触发的当前语料 Qdrant 构建入口，但尚无语料版本库、审核发布制、别名切换/回滚或完整清洗审校流水线；评估已有真实小样本报告、冻结的开发/保留数据及工程回归门禁，但数据未经过临床审核，覆盖量也小。三阶段都不能说已完整工程化。

下一步应先修正评估事实源和建立分层标签（主证据 / 安全交叉提醒 / 仅词面相关 / 不相关 / 无答案），再冻结经复核的开发集与 holdout，为候选召回和最终 rerank 分别定义 Recall@K、MRR、误选率及无答案误报门槛；随后将固定语料版本、chunk 配置、embedding 模型版本、索引构建结果和评估报告绑定成可追溯发布记录。最后再根据这些指标决定是否调整切分、query 处理或融合，而不是凭单条结果改阈值。医学内容的相关性和安全口径仍需有资质人员审核，工程标签不能替代临床验证。

## 代码范围

- `Bm25Retriever.index/Index.search`：不可变语料快照保存词频、文档频率及平均长度，沿用原n-gram、BM25参数、阈值和排序；原静态search兼容。
- `HybridKnowledgeCatalog.lexicalIndex/inspect`：复用同一语料快照索引；批准内容、正文、顺序或撤销使快照不同则重建。锁只覆盖索引获取/构建，不覆盖外部embedding或rerank请求。每次仍读取批准语料并作等值比较；不是持久化或倒排候选索引，不声称已完成后台向量任务。
- `QueryPlan.shadow`：分别保留当前问题与带消息ID的患者自述上下文，最多5条/4000字符，超窗丢弃整条最早记录而不截断否定或主体。调用方只能传患者自述；当前是独立影子契约，没有把历史语句混入实际检索。
- `CurrentRequestIntent.medicalRetrievalQuery`：已接入两条患者医学检索入口，移除明确的预约/挂号与就诊方向问句成分，保留余下当前轮文本。风险评估、科室路由和预约判断仍读取原文。纯业务意图输入保留原文，未建立症状同义词表，没有新增症状分诊规则。
- `Bm25IndexTest`：独立数学分数断言、排序、快照隔离、内容修订与撤销失效。
- `QueryPlanTest`：当前问题不拼接历史、否定/主体/时间原文保留、来源ID、窗口和输入边界。

## 2026-10-05 离线 Qdrant 构建入口（增量实现）

新增 `KnowledgeIndexBuildRunner`，只有显式设置 `ai.knowledge.offline-index.enabled=true` 才会执行。它读取与在线患者检索相同的已批准语料，调用同一个 `KnowledgeCatalog.syncIndex()` / `QdrantSemanticIndex` embedding 与幂等 upsert 路径；索引失败或索引数量不一致时以失败退出，不发布构建清单。成功后以原子文件替换写出 `target/knowledge-index-manifest.json`。schema v2 清单包含 collection、embedding 模型、实际分块策略参数、语料 SHA-256、活动片段数与已索引数，并在构建完成后从 Qdrant collection 只读取得 point count、状态、向量维度/距离和 HNSW 配置；不写正文、API key 或患者数据。collection point count 可能包含已不在当前活动语料的历史 point，不能用它替代活动片段数。语料顺序不影响指纹，正文/来源变化会改变指纹。

示例（PowerShell；密钥只设置在进程环境变量，不写命令行或文件）：

```powershell
$env:JAVA_HOME = 'E:\JDK17\jdk-17.0.1'
$env:AI_DB_URL = 'jdbc:h2:mem:rag-index'
# 在本机已有安全环境中设置 AI_EMBEDDING_API_KEY；不要把密钥写进文档或仓库。
$env:AI_EMBEDDING_MODEL = 'qwen3.7-text-embedding'
$env:AI_QDRANT_URL = 'http://127.0.0.1:6333'
$env:AI_QDRANT_COLLECTION = 'ai_hospital_knowledge_v2'
mvn -f backend/pom.xml '-Dspring-boot.run.arguments=--spring.main.web-application-type=none,--ai.knowledge.offline-index.enabled=true' spring-boot:run
```

这是可重复触发的离线 embedding/upsert 入口，但尚不是完整的语料发布系统：输入仍是仓库内手工整理 Markdown 与当前内存审批结果；网页抓取清洗、去重/切分质量审校、医院/租户级语料版本、Qdrant 别名原子切换与回滚、撤回时物理清理旧 point 均未实现。构建成功清单证明这次输入集合已送入索引适配器且读取到当时的 collection 配置，不代表召回质量或医学内容通过审核。

## 验证

JDK17、无AI_*，隔离副本执行mvn clean test -q -DforkCount=0：236项，235通过、1外部跳过、0失败/错误，44测试类。原frontend npm run build通过。首轮新增超窗测试错误使用3200字符，修正为4400字符后全量通过；不是业务规则改动。

之后用户重启原8081，确认backend target/classes中的Bm25Retriever$Index及QueryPlan class均为15:24构建。重启后再次运行六条管理端只读检索对照，语义服务READY、rerank均OK；选中标题、入选/淘汰结论及rerank分数与重启前一致（向量分数有小数末位变化）。偏头疼短句仍0候选，完整句仍选头晕资料；胸痛骨折候选仍被淘汰。该实测确认索引代码加载且没有明显改动召回结果，不证明BM25索引在生产语料规模下的性能提升，也不证明资料适用。

针对本批和受影响分诊流程执行JDK17无AI_*隔离`mvn clean test -q -DforkCount=0`：240项，239通过、1外部测试跳过、0失败/错误、45类。前端构建通过，原backend compile通过。首轮开发测试发现主体词会被清理以及“就医方向”没完全剥离，已按回归修正；本最终测试通过。新查询逻辑在编译后尚未由8081加载，也没有宣称真实问答已改善。

在补充`HybridRetrievalPipelineTest.reusesLexicalIndexUntilApprovedCorpusChanges`后，BM25索引、审批失效、QueryPlan、查询清理及分诊流程针对性测试通过。用户之后重启原8081，使用原5188→8081对合成患者实际跑三条消息：

| 输入 | 运行结果 | 说明 |
| --- | --- | --- |
| 偏头疼两天，暂时不挂号，想了解就医方向 | 0命中；23.2秒后OUTPUT_REFERENCE_INVALID | 清理后医学查询不再把头晕资料误作证据；缺少偏头疼语料仍未解决，且无资料时模型引用校验失败关闭 |
| 胸痛，想了解就医方向 | SAFETY_RULE，0检索 | 急症规则优先并跳过RAG；另有管理员只读RAG对照显示胸痛命中胸痛/红旗资料，骨折片段淘汰 |
| 流鼻涕两天，暂时不挂号，只问日常注意事项 | 2条流鼻涕资料命中；24.1秒后OUTPUT_MEDICATION_FILTER_CHANGED | 检索结果主题符合查询；回答仍被现有宽泛药品过滤拦截，具体拒绝正文不保存 |

此真实小样本说明查询清理改变了偏头疼检索：从长句命中不适用头晕资料到短症状查询零命中；它改善了不相关证据混入，不能弥补语料缺失。胸痛用例无法检查普通RAG路由，因为安全闸门正确提前结束。三轮均未产生可采用的LIVE医学正文，不能称问答验收通过。独立复核未完成，未提交。

## 后续

补标注检索集和QueryPlan影子对照，再接入多路查询与证据适用性。未验证前不切换查询输入，不调低阈值，不改安全规则、药品过滤或预约资格。知识审批/版本持久化与后台向量任务仍待实施。

## 2026-10-05 检索链路在线抽样（用户重启后）

通过原8081管理员只读详情接口，对10种唯一合成查询检查 BM25/Qdrant 候选、融合排序和百炼 rerank；不调用患者生成接口、不写预约/目录/语料。所有请求 `semanticStatus=READY`、`rerankStatus=OK`。

| 查询 | 观察结果 | 判断边界 |
| --- | --- | --- |
| 偏头疼两天 | 无候选 | 当前批准语料/召回未覆盖该短表达；不能仅凭此区分同义词、分块或索引原因 |
| 头疼两天 | 流鼻涕资料成为语义候选，rerank 0.395 后拒绝 | 候选池存在跨主题噪声，最终未采用 |
| 头痛 | 头晕资料 rerank 0.509 后入选 | 疑似跨主题误选；需看正文及标注判断，不将标题当作全文证据 |
| 偏头痛 | 头晕资料 rerank 0.387 后拒绝 | 无最终结果，但召回存在语义近邻噪声 |
| 偏头疼两天 + “想了解就医方向” | 头晕资料 rerank 0.653 后入选 | 相比短句结果发生变化，是需优先复现的查询清理/输入影响问题 |
| 胸痛（短句及加就医方向） | 胸痛、急诊红旗、消化系统标题均入选；胸痛会话句还出现鼻涕/呼吸等候选 | 标题提示可能存在跨主题候选/最终入选；本次未保存或人工核对 excerpt，故不能仅凭标题认定 chunk 错或内容无关 |
| 流鼻涕两天（短句及加日常注意事项） | 两个同标题鼻涕片段入选 | 与查询主题一致；是否重复需比较正文，当前未比较 |
| 骨折 | 骨折资料以 rerank 0.808 入选 | 本查询命中预期主题 |

这批在线结果最初只能说明排序输出存在可疑项；后续只读审计发现：

- `tools/knowledge-sync` 抓取来源快照、哈希与变化，不会把网页自动发布进语料。RAG 运行语料是仓库内人工整理的 11 篇 Markdown；当前应用读取后只提取标题/来源/主题/正文并做有限换行与空白处理，不是自动网页清洗流水线。
- 运行时管理员只读端点返回 11 篇 READY 文档、共 12 个片段；Qdrant collection count 也为 12。逐项对照运行时详情与 Qdrant scroll payload 后，已检查的标题、source、excerpt 精确一致；没有发现这些样本发生跨文档 point 错配。点 ID 是 source/title/excerpt 与 embedding 配置的确定性 UUID，不含独立 chunk ID 或知识版本字段。
- 后续以脚本逐项对比全部 payload：runtime segments=12、Qdrant points=12、精确匹配 12、missing=0、extra=0。实际片段字符长度 82–227，中位数 159；当前这批语料没有任何片段超过 300 字。因此 420 字上限/60 字重叠的长文切分路径未在当前线上语料实际触发，无法用本次运行样本证明长文切分质量。
- 运行中 collection 状态为 green、optimizer ok，向量维度 1024、Cosine；配置含 HNSW `m=16`、`ef_construct=100`、`full_scan_threshold=10000`。当前仅 12 points，向量数据量远低于 10000 KB 阈值，故本批查询由 Qdrant 全扫描处理；没有证据表明 HNSW 构建/参数导致错召回。本次核查时该 collection 由旧代码创建，参数来自 Qdrant 配置/默认值；本批新增配置只作用于之后新建的 collection，不会自动 patch 正在使用的 collection。
- 更正胸痛结果判读：消化系统片段正文明确含“胸痛同时出现恶心、呕吐和大汗时不能简单归为消化问题”的提醒；它虽非胸痛主资料，但属于相关的鉴别提醒，不能仅凭标题判为无关召回。
- 更正长查询归因：之前对照调用管理员 `/search/details`，该接口把 query 原样交给检索，不执行患者端 `CurrentRequestIntent.medicalRetrievalQuery` 清理。因此该长句结果只能说明原始检索对通用措辞敏感，不能证明患者端实际发出的查询也包含这些通用词。
- 头痛/偏头痛方面，当前语料没有专门的偏头痛资料；头晕片段正文确有“剧烈头痛”红旗内容，故它对急症提示有局部相关性，但不能充当一般偏头痛问答或分诊依据。`头痛` 的 rerank 0.509 仅略过 0.5 阈值，体现的是相关性粒度/边界需要评测，尚不足以定为索引损坏。

因此目前没有证据支持“Qdrant 向量损坏或 point 指向错误”；更明确的已知缺口是知识覆盖与片段适用范围，及尚未建立标注评测集来判断 rerank 的边界。该核查仍不等于语料来源内容经医学审核。下一步应以人工标注的 query-chunk 相关性集分别评估候选召回与最终选择，并另走患者端实际路径验证查询清理；不得因此调低阈值或重建索引。

## 2026-10-05 患者真实路径对照

随后经原 5188 → 8081 患者 API，以合成账号建立 3 个会话（未预约、未改目录/语料），只打印证据元数据，不记录回答正文：

| 当前轮输入 | 患者链路结果 | 验收判断 |
| --- | --- | --- |
| 偏头疼两天，暂时不挂号，想了解就医方向 | `knowledgeHits=0`、`NO_MATCH`、无引用；LLM 输出经安全校验 | 患者路径没有复现管理员原始 query 的头晕误选；专用偏头痛资料仍缺失 |
| 头痛两天，只想了解常见原因 | `knowledgeHits=0`、`NO_MATCH`；模型草稿含无效引用，被阻断后返回安全兜底 | 没有把头晕 chunk 错作最终引用；无资料生成格式仍可能失败关闭，LLM能力/协议质量不是检索命中的证据 |
| 流鼻涕两天 | `knowledgeHits=2`、`MATCHED`；引用完整性与支持复核通过，仅 E1 被采用 | 这条已覆盖症状从患者入口到证据采用；模型总耗时约29秒，体验仍慢 |

该实测厘清：之前管理员 `/search/details` 诊断调用直接把原始长句交给检索，患者链路则先移除服务意图/方向用语；因此管理端“偏头疼长句选中头晕”不能外推为患者实际检索行为。患者检索当前对偏头疼是无匹配，不是错引头晕；“头痛”零资料时的问题发生在 LLM 输出无效引用，安全兜底正确阻止了错误引用。后续质量工作要分别验收患者实际 QueryPlan、检索覆盖和无证据响应，不能混用管理端探针与患者链路结果。

## 2026-10-05 工程主题检索基线（15 个只读查询）

用现有文档标题/主题预先指定每个正向查询的“主主题文档”，并设置偏头痛、鼻涕↔骨折交叉对照。该标注只衡量工程主题相关性，不是临床 gold set；输入管理员 raw-search，不代表患者请求原文。全部 embedding/rerank 请求正常。

- 11 个主主题正向查询中，预期主题文档 **11/11 进入最终选择**（急诊红旗、胸痛、脑卒中、呼吸、消化、头晕、基础急救、骨折、咽痛、痛经、流鼻涕）。
- `偏头痛 两天` 对照最终无选择，头晕候选 rerank 0.403 被拒；与患者链路偏头疼无资料命中一致。
- `流鼻涕` 与 `手腕骨折` 两个交叉对照均只选本主题文档，没有鼻/骨折互相串入最终结果。
- 精度风险被具体定位到 `头晕 眩晕 恶心`：头晕主文档正确入选，但胸痛文档（分数 0.510）和消化文档（0.540）也越过统一 0.5 阈值。它们包含共享症状/红旗词，但不是该查询的主主题资料。类似地急诊查询也会召回多个跨主题但可能安全相关的资料。
- 胸痛查询选择消化文档不是明确错误：其正文专门提醒胸痛伴恶心/呕吐/大汗不能简单归类为消化问题；应按正文用途判断，而非标题相同与否。

初步工程结论：当前索引与主主题基本匹配，观察到的主要缺陷不是目标文档普遍召回失败，而是单一全局 rerank 阈值会把“正文提及/安全交叉提醒”与“本轮主主题证据”一起展示。是否为错误证据还需人工逐段标注。后续应保留证据用途/主题适配标注，区分主证据、危险信号交叉提醒和仅有词面关联；在此之前不改阈值或用分数宣称语义准确率。

同日追加同一基线脚本的头痛/恶心变体后，总计 16 条工程查询：有主主题标签的 14 条均命中对应文档；偏头痛无覆盖对照无最终选择；“头痛”无专属主文档标签，但头晕资料以 0.509 被选中（仅能支持其正文中的剧烈头痛红旗，不足以支持一般头痛答复）。16 条中 7 条出现额外选择，需人工按主证据/交叉安全提醒/偶然提及分别标注，不能直接计成 7 个错误。

另走患者链路合成实测“恶心”与“头晕”：单独恶心检索 3 份资料（消化、头晕、胸痛），模型约 35 秒超时，零引用被采用，`MODEL_UNAVAILABLE` 安全兜底；单独头晕只检索头晕资料，E1 通过校验/支持度复核，模型约 26.7 秒。故单一含糊症状目前可能把多个交叉提及片段送入生成，并且等待时间长；这比索引错配更像“适用性选择 + 供应商延迟”问题。测试正文未落日志，仅保留状态元数据。

新增可重复运行脚本 `scripts/evaluate-rag-topic-baseline-live.cjs`，只调用管理员只读检索详情端点，使用合成 query，不修改预约、目录或知识源。它的标签是按本地语料主题建立的工程基线，不是医学审核或临床 gold set。

## 2026-10-05 增量：无资料生成约束与误拦截修复

- `MedicalAnswerInstructions.generation(request, hasReferences)` 按本轮是否有真实引用切换输出契约。零引用时只允许 `LIMITATION` 且引用列表为空，明确禁止生成具体医学事实；`OptionalNarrationModel` 以实际检索片段数选择该模式。避免无命中时模型照抄提示示例里的虚构 E1。
- 药品过滤从“句子提到用药/药物就过滤”收窄为带建议动作及具体药物/剂量的指令型表达；“具体用药由医生决定”及一般非药物护理用语不再因关键词被误拦。明确的服药/剂量建议仍由结构化内容校验阻断；这不是医学安全完整性证明。
- 新测试覆盖零证据只能输出限制说明，以及中性说明可通过、具体服药建议仍被拦截。
- 全量测试同时暴露人工导诊并发重复申请的现存唯一键竞争：`TriageConversationService.requestHumanReview` 现在在唯一键冲突后读取并返回已创建记录；找不到记录则继续抛出原异常。现有并发幂等测试覆盖该分支。

重启后的真实只读核查及合成会话复验发现：

- 当时 `/api/admin/knowledge/runtime` 显示模型与 Qdrant 配置已加载、模型名 `qwen3.7-flash-2026-07-15`、embedding=`qwen3.7-text-embedding`、rerank=`qwen3.7-text-rerank`。该端点明确只是配置/最近一次状态，不是实时健康检查。
- 独立管理员只读检索在服务可用时，短查询“流鼻涕两天”命中两条流鼻涕资料，语义检索 READY、rerank OK；同一时段患者会话实际请求的观测记录则出现 `SEARCH_UNAVAILABLE` 和 `RERANK_UNAVAILABLE`，候选有2条但 required-rerank 导致 selected=0。故当时不能说“知识库没有鼻部资料”。
- 患者请求诊断曾把上述依赖阻断标成 `NO_MATCH`，因为 `RuleBasedTriageEngine` 只看本地工具是否抛异常，没有检查 `Retrieval.message` 的 `DEPENDENCY_BLOCKED_*`。已修正：现在把 required embedding/rerank 故障记为 `DEPENDENCY_UNAVAILABLE`，仅依赖正常且候选为空才记 `NO_MATCH`。
- 同批合成患者调用观察到一次 LLM `MODEL_IO_ERROR/IOException`，另一次无资料回答输出不存在的引用ID，命中现有引用完整性拦截。已补安全兜底：零引用时模型不可用、输出非法引用/结构或内容校验未通过，将返回“知识库未命中”或“资料服务暂不可用”的不同限制说明；原始失败码仍保留，且不会把依赖故障伪装成无资料。此分支尚待新源码加载后的在线复验。
- 新增 [test-rag-answer-guard-live.cjs](../scripts/test-rag-answer-guard-live.cjs)，只使用合成患者文本；输出诊断字段，不打印回答原文，不创建预约或修改目录/知识源。遇到外部检索依赖不可用会报告“无法验收命中”，不会误判为语料缺失。

本轮JDK17隔离后端副本 `mvn clean test -DforkCount=0` 最终245项：244通过、1项外部测试跳过、0失败/错误（48类）。前端隔离构建通过；`git diff --check` 通过。由于运行中的 IDEA 占用原 `backend/target/classes`，未覆盖或停止服务。以上最新分类及安全兜底改动尚未由8081进程加载，须按原方式重启后继续真实百炼/Qdrant复验。真实检索质量仍未完成评测，不能据此宣称RAG已修好。

### 离线索引构建批次验证

JDK17、无 `AI_*` 环境变量，在隔离副本执行完整 `mvn clean test -q -DforkCount=0`：252 项，251 通过、1 个 opt-in 外部服务用例跳过，0 失败/错误。新增 3 个测试覆盖语料指纹顺序稳定与内容变更、清单不包含片段正文、索引失败时不发布清单。前端未因本批改动；隔离 `npm run build` 已通过。测试使用本地替身验证控制流，没有调用百炼、Qdrant 或患者在线接口，也没有重启 8081。

### 新建 collection 的 HNSW 参数

`QdrantSemanticIndex.ensureCollection` 对新建 collection 显式传入 `hnsw_config`：`m=16`、`ef_construct=100`、`full_scan_threshold=10000 KB`；三项可分别由 `ai.qdrant.hnsw.m`、`ai.qdrant.hnsw.ef-construct`、`ai.qdrant.hnsw.full-scan-threshold-kb` 覆盖。数值与 Qdrant 文档示例的默认配置相同，不是本项目评测调参结果；在线 search 不额外指定 `hnsw_ef`。已存在 collection 不会被此逻辑自动更新，以避免应用启动/请求时触发后台重建。Qdrant 对小于 dense `full_scan_threshold` 的 segment 可直接全扫描；因此当前小语料走全扫描是预期行为，HNSW 图索引需要达到索引条件并由优化器完成。该设置只影响查询性能/召回折中，不能修正语料缺失或内容不相关。参考 [Qdrant indexing](https://qdrant.tech/documentation/manage-data/indexing/) 与 [collection 创建 API](https://api.qdrant.tech/master/api-reference/collections/create-collection)。

本项在隔离 JDK17 副本重跑完整 `mvn clean test -q -DforkCount=0`：253 项，252 通过、1 个 opt-in 外部服务用例跳过，0 失败/错误；`QdrantSemanticIndexTest` 两项均通过，验证默认值及属性覆盖。未调用外部 embedding/Qdrant，也未改正在运行的 collection。

### 重启后的 Qdrant point 复用

`QdrantSemanticIndex.ensureIndexed` 现在在调用 embedding 前，先按稳定 point ID 批量向当前 collection 读取候选点，并逐字段核对 `title/source/excerpt`。ID 与完整 payload 均相同的片段会登记为已索引并复用；缺失、ID 不同或 payload 不匹配的内容才会重新 embedding/upsert。因此服务重启后可以避免对未变更的已发布知识重复请求 embedding；文档变化仍会因内容参与 point ID 而生成新向量。读取 Qdrant 失败不会假定点存在，会维持索引不可用状态交给既有检索策略处理。撤回/修订产生的旧 point 暂不物理删除，活动语料的 ID 过滤仍阻止其成为当前证据。

新增 `QdrantRemoteIndexReuseTest` 用本地 HTTP 服务验证：第一次构建 embedding/upsert 一次；模拟进程重启后精确相同语料不再调用 embedding/upsert；正文变化后才再次调用。该协议测试不访问真实 Qdrant/百炼，也不代表并发负载或线上 collection 的当前状态。

### 分阶段检索评估指标

`RagLiveIntegrationTest` 的每条合成样本现在同时输出 BM25 候选、Qdrant 候选、RRF 排序候选与最终 selected 的 Recall@K、MRR、候选数及显式 unrelated-control 命中。这样可以区分“词法/向量都没召回”“候选召回了但融合排序靠后”“候选正确但最终选择变化”等故障层，而不只看最终结果。当前资料集原有 `relevant` / `forbidden` 标题标签暂分别视为预期工程主题标题 / 显式无关对照，指标只作诊断，未给候选阶段新增质量阈值。

`target/rag-live-summary.json` 同时写入该次评估的 index manifest 与明确的 BM25/向量阈值、RRF-k、候选数、最终 K、rerank 模型/门槛；它们用于将结果绑定到语料哈希、分块策略和实际 collection 参数。报告不写 API key。普通未启用 live 环境时该产物不会生成，不能将旧 summary 当成当前运行健康状态。

这不是分级医学相关性标注：目前没有经临床审阅的“主证据 / 安全交叉提醒 / 仅词面相关”标签；`unanswerable` 仍是工程样本的无答案用例，不代表全面覆盖现实患者问题。阶段指标只有在 `AI_RAG_LIVE_TEST=true` 时才会从真实检索调用生成，本批本地测试仅验证计算语义，不访问百炼或 Qdrant。
