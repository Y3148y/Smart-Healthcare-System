# 知识源工具

本工具维护来源记录、准备本地待审资料包，并在显式选择后将资料创建为 `PENDING_REVIEW`。普通采集和待审导入不审批资料；仅下文显式授权的隔离本机测试准入命令可调用审批接口。工具不把医学正文打包进应用、不写应用资源目录。来源快照和生成资料包保存在本机忽略目录。

目录统一为项目根knowledge/sources/（原始网页快照）和reports/knowledge-sync/（采集报告、intake待审包、proposals工作单）；11篇公开示例正文及必要来源文本见knowledge/corpus/。本机抓取缓存和待审包被Git忽略；公开语料的许可边界见knowledge/LICENSE.md。

## 当前运行语料

应用包不含内置医学摘要；空数据库是合法状态。患者检索只使用数据库里已审批的资料，开发者本机资料目录不会自动加载。设计与限制见 [RAG 说明](../../docs/RAG.md)。

`sources.json` 只登记来源，不是运行语料清单。采集状态保存在 `reports/knowledge-sync/state.json`，首次运行从空状态开始；不会继承其他开发者的抓取历史。

## 命令

| 命令 | 网络 | 作用 |
| --- | --- | --- |
| `npm test` | 否 | 测试来源抓取与待审导入包校验。 |
| `npm run sources` | 否 | 输出登记的来源 ID、URL 和采集方式。 |
| `npm run fetch -- --dry-run` | 是 | 抓取可采集来源，不更新本地状态。 |
| `npm run fetch` | 是 | 抓取全部自动采集来源并保存本地快照和哈希。 |
| `npm run fetch -- --cycle` | 是 | 仅抓取到期（含尚未采集）的自动采集来源，按登记的复查周期筛选。 |
| `npm run fetch -- <id>` | 是 | 只抓指定来源。 |
| `npm run diff` | 否 | 在本地生成来源变更报告。 |
| `npm run proposals` | 否 | 为来源变更生成本地编辑工作单。 |
| `npm run intake` | 否 | 校验本地快照并生成待审 JSON 包。 |
| `npm run import:local -- <packet.json>` | 否 | 仅校验一个资料包，不访问后台。 |
| `npm run import:local -- <packet.json> --apply` | 本机后台 | 创建一条 `PENDING_REVIEW` 资料；管理员令牌只从 `KNOWLEDGE_ADMIN_TOKEN` 环境变量读取，不审批、不覆盖。 |
| `npm run approve:test` | 否 | 校验公开11篇及来源绑定，不改原件/后台。 |
| `node approve-test-corpus.mjs --apply` | 本机测试后台 | 显式导入、标记本机测试准入、审批并检查每篇切分片段；需 `KNOWLEDGE_TEST_APPROVAL=YES`。 |
| `npm run audit:corpus` | 否 | 检查应用资源目录是否仍有旧 Markdown 资料。 |

导入器只访问配置的本机 API 地址，并拒绝 HTTP 重定向。标题与正文生成稳定幂等键：相同请求重试返回原记录，相同键携带不同元数据返回 409；数据库唯一键约束并发请求。导入器不会抓取来源网页，也不会猜补缺失元数据。

仅作开发测试时，维护者允许把这11篇示例资料导入**隔离的本机测试数据库**并通过现有管理员审批接口。先在该测试后台取得管理员令牌，显式设 `KNOWLEDGE_ADMIN_TOKEN`、`KNOWLEDGE_IMPORT_BASE_URL`（例如临时测试后端 `http://127.0.0.1:8082/api/`）和 `KNOWLEDGE_TEST_APPROVAL=YES`，再从本目录执行 `node approve-test-corpus.mjs --apply`。直接调用 Node 可避免部分 Windows PowerShell `npm` 包装器未转发 `--apply` 参数而意外只做 dry-run。脚本先按原始 `pending` 元数据导入，再将数据库中的使用许可声明改为 `permitted`，其依据仅写“维护者授权本机测试，未完成临床/翻译/适用范围审核”，最后调用既有审批接口并核对片段非空；不会改动或重新哈希 `knowledge/corpus/` 原件。要复验撤回，请在管理员页面逐篇撤回；不要把测试库、令牌或审批回执上传仓库。

此命令只能连回环地址，但**不能识别该地址是否意外指向共享/真实患者环境**；操作者必须先确认使用独立数据库和Qdrant集合。它不是临床审核、法律许可判定或医院发布路径。现有审批状态 `READY` 只表示该测试库可检索，不能用于面向真实患者的医学服务。旧库已有同文不同元数据导入或非本脚本的批准时命令拒绝/冲突，不会覆盖。

## 隔离全链复验（模拟向量）

仓库根目录的 `node scripts/test-corpus-lifecycle.mjs --serve-embedding` 启动随机回环端口的本机向量夹具，输出接口地址并保持运行。它为所有文本返回相同二维向量，只检查索引生命周期，不具备医学语义或质量评估能力。

另启动一个**空的独立MySQL数据库**上的测试后端，不能复用日常8081或真实患者实例。沿用项目启动命令，但设 `AI_MODE=demo`、`AI_AGENT_MODE=OFF`、`AI_LEXICAL_BACKEND=elasticsearch`、`AI_RERANK_ENABLED=false`，为 `AI_DB_URL`、`SERVER_PORT`、`AI_QDRANT_COLLECTION`、`AI_ELASTICSEARCH_INDEX_PREFIX` 选择独立值；集合和索引前缀均不得与日常服务相同。`AI_EMBEDDING_BASE_URL` 使用上一步输出的本机地址，`AI_EMBEDDING_MODEL=synthetic-corpus-lifecycle-v1`，`AI_EMBEDDING_API_KEY` 只需在进程环境设非空测试占位值，不使用真实密钥。MySQL、ES和Qdrant须已就绪。

在探针终端显式设置 `CORPUS_TEST_BASE_URL` 为这个测试后端的回环HTTP origin、`CORPUS_TEST_PASSWORD` 为非空演示登录值、`CORPUS_TEST_ISOLATED=YES`、`AI_MODE=demo`、`AI_AGENT_MODE=OFF`，然后从仓库根目录执行：

```powershell
node --test scripts/corpus-lifecycle.test.mjs
node scripts/test-corpus-lifecycle.mjs --apply
```

探针验证固定11篇/78片段的待审导入、测试许可/审批、逐篇索引计数、ES与Qdrant候选、导入重放身份不变、撤回后待审与空语料状态。结果明确为 `PASSED_SYNTHETIC_LIFECYCLE`、`qualityMetrics:null`，不会发送患者咨询或调用外部LLM/rerank。模拟向量可能使最终片段与主题无关，因此不据最终排序断言相关性，也不发布召回率或忠实度。CI在同一个独立数据库/集合/索引命名范围运行此链路。

脚本拒绝非空知识库或其他embedding模型；这些检查和环境声明**不能证明后台数据库、索引或embedding URL实际隔离**，操作者必须核对真实启动配置。失败仅输出固定阶段名，不回显正文或凭据；不要对已有写入的失败实例盲目重跑，应保留日志并重新准备空的隔离实例。成功后11篇仍留在该测试库但已撤回，旧向量和ES快照不自动删除；停掉夹具和测试后端，只按事先记录的测试资源名清理，不运行通配或日常数据卷删除命令。

## 采集和审核边界

本地待审导入也接受 `.md`：顶部两行 `---` 包围严格JSON。最小头包含schemaVersion、documentId、title、approvalStatus=PENDING_REVIEW、clinicalReviewStatus=NOT_REVIEWED、provenanceFile和provenanceSha256。来源/范围metadata、日期、采集指纹及章节来源放同目录provenance.json；指纹绑定该文件及全部声明的英文快照。旧内联metadata格式仍可读取，但两套metadata不能并存。导入器将受绑定的文档ID、provenance/正文哈希、来源快照清单和抽取告警放入 `metadata.intakeAudit`，与正文一同提交；只有分隔符之后正文进入切分/向量化，审核说明不进入正文。后端保存来源/适用元数据与该审计清单；原始网页正文和章节到来源的逐段映射仍留在本地。客户端哈希记录不等于服务端独立认证来源。

早期版本导入的同标题/正文资料可能缺少 `intakeAudit`。本机测试准入命令只对**内容完全一致、具有原 `TEST_ONLY_LOCAL` 依据且唯一命中**的记录执行撤回→补审计清单→重新审批；即使旧记录仍为待审，只要 409 冲突且没有该测试标记，也拒绝自动接管，须人工核查。非测试审批、内容变化或身份不唯一均拒绝自动修复。普通 `import:local` 遇到旧记录的元数据冲突仍返回409，不能静默覆盖。

使用 `npm run import:local -- document.md` 先离线校验；加 `--apply` 才创建待审记录。contentKind沿用后端 reviewed_summary 枚举，这个名称不表示已经完成医学审核。

`node validate-document.mjs --corpus` 跨平台一次校验全部公开正文及来源快照；传入具体MD路径可校验单篇或多篇。命令只读、无网络，不导入或审批；拒绝路径穿越、缺失来源或指纹漂移。从仓库根目录，在JDK17编译后运行 `java --class-path backend/target/classes tools/knowledge-sync/ChunkInspection.java knowledge/corpus`，检查实际切分片段的长度、偏移、哈希、确定性及每个原文段落完整落在一个片段内；`--self-test` 验证完整短段可通过、被切开的长段被拒绝。长段落如果跨片段，会要求人工检查而非静默通过。短片段数量仅供人工审视，段落完整不等于临床条件完整。新修订必须重新审核、索引和评估，旧版本查询结果不能替代新版本验收。

新增NLM来源仅提取MedlinePlus健康主题页的topic-summary区域；找不到或区域重复则拒绝，不回退整页。依据官方使用说明，该区域政府编写的摘要可复用；百科、药物、图片及外链内容不在此许可范围。本机英文摘要与中文整理稿仍需逐段核查，来源可复用不等于临床审核完成。历史受限百科ID与新感冒摘要ID分离。

- 来源登记表不是医学知识库。URL 或抓取成功不能证明内容完整、授权充分、医学准确或适用于某位患者。
- 抓取遵守工具中的 robots、重定向和限速检查。来源不可达、被拦截或抽取不完整时，不生成替代正文。
- 来源 HTML 缓存、未批准的新整理稿和评估报告保存在本机忽略路径；仅用户已授权的11篇示例正文及必要来源文本公开。新增语料须逐项核实许可，不因目录例外自动批准上传。
- 导入资料保持待审，直到授权管理员完成审核和审批。项目操作者确认不等于专业临床审核。
- 检索链路评估入口在 `tools/rag-eval`，不使用另写一套检索算法的模拟器；作者整理的开发集只能作为开发证据。

## 2026-10-22 来源扩展

新增登记8个MedlinePlus健康主题摘要：腹泻、便秘、尿路感染、肾结石、脱水、背痛、2型糖尿病及胃食管反流病。前7个已通过现有采集器取得原始HTML并完成本机中文待审整理；GERD官方地址已核对为 `https://medlineplus.gov/gerd.html`，但本机采集因网络/robots获取失败未取得快照，保留待核实，不生成替代正文。

新增稿位于本机忽略的 `reports/knowledge-sync/expansion-2026-10-22/`，按系统与主题分目录，含中文正文、英文摘要和绑定来源/快照指纹的provenance；批次manifest记录各篇路径和状态。健康主题摘要复用依据为MedlinePlus官方使用条款，排除百科、药物专论、图片和第三方外链。中文为精简改编，保留成人范围、医学/翻译/适用审核未完成及禁止公开发布的阻断。没有导入、审批、建立索引或修改活动知识版本。

公开语料仍为原11篇/78片段；原检索评估不能用来证明新增主题效果。新增7篇可逐篇使用上文 `validate-document.mjs` 校验，再按待审导入流程复核；现有11篇测试准入授权和脚本不扩展到这批新稿。
