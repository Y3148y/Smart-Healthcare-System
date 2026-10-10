# 知识源工具

本工具维护来源记录、准备本地待审资料包，并在显式选择后将资料创建为 `PENDING_REVIEW`。它不把医学正文打包进应用、不写应用资源目录，也不审批资料。来源快照和生成资料包保存在本机忽略目录。

目录统一为项目根knowledge/sources/（原始网页快照）和reports/knowledge-sync/（采集报告、intake待审包、proposals工作单）；中文业务资料见knowledge/corpus/。快照与正文均被Git忽略，不公开提交。

## 当前运行语料

应用包不含内置医学摘要；空数据库是合法状态。患者检索只使用数据库里已审批的资料，开发者本机资料目录不会自动加载。设计与限制见 [RAG 说明](../../docs/RAG.md)。

`sources.json` 只登记来源，不是运行语料清单。采集状态保存在 `reports/knowledge-sync/state.json`，首次运行从空状态开始；不会继承其他开发者的抓取历史。

## 命令

| 命令 | 网络 | 作用 |
| --- | --- | --- |
| `npm test` | 否 | 测试来源抓取与待审导入包校验。 |
| `npm run sources` | 否 | 输出登记的来源 ID、URL 和采集方式。 |
| `npm run fetch -- --dry-run` | 是 | 抓取可采集来源，不更新本地状态。 |
| `npm run fetch` | 是 | 抓取到期来源并保存本地快照和哈希。 |
| `npm run fetch -- <id>` | 是 | 只抓指定来源。 |
| `npm run diff` | 否 | 在本地生成来源变更报告。 |
| `npm run proposals` | 否 | 为来源变更生成本地编辑工作单。 |
| `npm run intake` | 否 | 校验本地快照并生成待审 JSON 包。 |
| `npm run import:local -- <packet.json>` | 否 | 仅校验一个资料包，不访问后台。 |
| `npm run import:local -- <packet.json> --apply` | 本机后台 | 创建一条 `PENDING_REVIEW` 资料；管理员令牌只从 `KNOWLEDGE_ADMIN_TOKEN` 环境变量读取，不审批、不覆盖。 |
| `npm run audit:corpus` | 否 | 检查应用资源目录是否仍有旧 Markdown 资料。 |

导入器只访问配置的本机 API 地址，并拒绝 HTTP 重定向。标题与正文生成稳定幂等键：相同请求重试返回原记录，相同键携带不同元数据返回 409；数据库唯一键约束并发请求。导入器不会抓取来源网页，也不会猜补缺失元数据。

## 采集和审核边界

本地待审导入也接受 `.md`：顶部两行 `---` 包围严格JSON。最小头包含schemaVersion、documentId、title、approvalStatus=PENDING_REVIEW、clinicalReviewStatus=NOT_REVIEWED、provenanceFile和provenanceSha256。来源/范围metadata、日期、采集指纹及章节来源放同目录provenance.json；指纹绑定该文件及全部声明的英文快照。旧内联metadata格式仍可读取，但两套metadata不能并存。只有分隔符之后正文进入切分/向量化；审核说明不进入正文。后端保存来源/适用元数据与导入审计清单；原始网页正文和章节到来源的逐段映射仍留在本地。客户端哈希记录不等于服务端独立认证来源。

使用 `npm run import:local -- document.md` 先离线校验；加 `--apply` 才创建待审记录。contentKind沿用后端 reviewed_summary 枚举，这个名称不表示已经完成医学审核。

`node validate-document.mjs <document.md> [...]` 离线核验追溯记录及全部来源快照，支持多个文档，拒绝路径穿越、缺失来源或指纹漂移。新修订必须重新审核、索引和评估，旧版本查询结果不能替代新版本验收。

新增NLM来源仅提取MedlinePlus健康主题页的topic-summary区域；找不到或区域重复则拒绝，不回退整页。依据官方使用说明，该区域政府编写的摘要可复用；百科、药物、图片及外链内容不在此许可范围。本机英文摘要与中文整理稿仍需逐段核查，来源可复用不等于临床审核完成。历史受限百科ID与新感冒摘要ID分离。

- 来源登记表不是医学知识库。URL 或抓取成功不能证明内容完整、授权充分、医学准确或适用于某位患者。
- 抓取遵守工具中的 robots、重定向和限速检查。来源不可达、被拦截或抽取不完整时，不生成替代正文。
- 来源 HTML、抽取正文、患者科普稿和评估报告保存在本机忽略路径，不提交到公开仓库。
- 导入资料保持待审，直到授权管理员完成审核和审批。项目操作者确认不等于专业临床审核。
- 检索链路评估入口在 `tools/rag-eval`，不使用另写一套检索算法的模拟器；作者整理的开发集只能作为开发证据。
