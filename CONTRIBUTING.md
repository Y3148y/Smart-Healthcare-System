# 贡献说明

公开提交应围绕可验证的功能、修复或必要文档。保持改动范围明确，提交前检查 diff，避免把无关在制文件一起提交。

项目原创代码与技术文档使用根目录的 [Apache License 2.0](LICENSE)。医学资料按 [独立许可与署名边界](knowledge/LICENSE.md) 处理；贡献代码不等于获准导入或发布医学语料。

- 保留源码、自动化测试、可复现数据集、运行配置和必要技术说明。
- 不提交密钥、患者数据、构建产物、个人调试记录、协作交接或未整理截图。
- 使用 JDK 17 运行后端测试，并运行前端构建；外部模型测试显式启用。
- 功能说明与代码一致，清楚说明模拟数据、未实现接口和验证范围。
- 发布前检查提交后的代码，而非只检查含未提交文件的工作目录。

提交前运行 `node scripts/check-public-files.cjs` 核对已跟踪文件路径，并运行 `node scripts/check-public-content.cjs` 检查常见凭据形态；CI 也会执行。新增公开技术文档须同步维护脚本中的 `docs/` 白名单，新增语料须先逐项确认许可并限定在 `knowledge/corpus/` 的四类文本资产。这两项检查只覆盖路径和少数凭据模式，不代替完整密钥扫描、版权核验或医学审核；提交前仍需人工检查 diff 和历史记录。

内部工作文件由 .gitignore 排除。新增公开文件时先确认其用途与维护成本。

## 测试入口

默认回归不需要模型凭据；完整命令见 [README](README.md#开发与测试)。以下按用途保留测试入口，不将一次性调试脚本作为第二套测试框架。

| 用途 | 入口 | 数据与外部调用 |
| --- | --- | --- |
| 后端契约、权限、模型适配器 | `backend` 下 `.\mvnw.cmd test`（JDK 17） | 默认无外部模型；显式集成组另需隔离数据库/索引 |
| 前端组件契约、构建 | `frontend` 下 `npm test`、`npm run build` | 无外部模型 |
| 知识来源、导入与切分 | `tools/knowledge-sync` 下 `npm test`、`npm run audit:corpus` | 离线；来源下载须另行显式执行 |
| 检索、答案、意图/工具评分器 | `tools/rag-eval` 下 `npm test` | 合成夹具验证评分契约，不是医学质量成绩 |
| SSE 分片解码 | `node scripts/test-progress-client.cjs` | 无服务；使用前端已安装的 TypeScript |
| 观察回看与运行链路组件 | `node scripts/test-observation-trace-ui.cjs` | 随机本机端口、真实 Chromium、合成 GET 拦截 |
| 容器业务闭环 | `scripts/test-container-smoke.cjs`、`scripts/test-container-journey.cjs` | 隔离 demo/OFF；真实 HTTP 合成写入，CI 执行 |
| 公开 11 篇资料生命周期 | `scripts/test-corpus-lifecycle.mjs` | 隔离 MySQL/ES/Qdrant；等向量夹具，不验证语义质量 |
| 知识审批、预约确认浏览器闭环 | `scripts/test-knowledge-approval-ui.cjs`、`scripts/test-booking-confirmation-ui.cjs` | 隔离 demo/OFF；真实浏览器写入，CI 执行 |
| 目录、本人资料、人工导诊浏览器检查 | `scripts/test-catalog-ui.cjs`、`scripts/test-patient-profile-ui.cjs`、`scripts/test-human-review-ui.cjs` | 可选隔离本机写入；不是默认 CI 组 |
| 已配置模型的调用与用量关联 | `scripts/test-triage-audit-live.cjs` | 显式本机目标；会请求模型、留下合成会话 |
| 预约非法输入、页面只读巡检 | `scripts/test-booking-input-live.cjs`、`scripts/test-ui-readonly.cjs` | 前者仅应向隔离实例发送非法请求；后者登录并读取页面 |

写入探针需 `--apply`，并设置各脚本要求的本机地址、模式与测试密码；仅 `--apply` 不能证明实例已隔离。容器、知识生命周期及浏览器探针的具体配置见 README、知识工具说明和 `.github/workflows/verify.yml`。保留的可选浏览器脚本覆盖不同交互，不因 HTTP 测试存在就删除其 UI 断言；真实模型调用也不能由 demo 探针代替。

`scripts/*.test.cjs` / `scripts/*.test.mjs` 验证公开文件、凭据形态、锁文件、目标限制、容器与维护工具的机械契约。CI 是受维护测试入口的清单，新增脚本应说明用途、依赖、写入边界及失败行为，避免复制某次运行的模型名、科室和排序作为永久断言。

## 分支与版本

- main 保存经过检查、可以独立构建的公开基线；功能开发和在制修复不直接推送 main。
- 分支按实际改动命名，例如 feature/knowledge-ingestion、fix/booking-idempotency、docs/repository-cleanup。不要因一次讨论就建立没有明确交付范围的分支。
- 合并前检查提交中的代码、后端 JDK 17 测试、前端构建、公开文件范围与文档一致性。真实模型或医院接入的验收单独记录，离线测试不能替代。
- 产品版本使用带说明的 vX.Y.Z 标签，仅在版本范围和验收完成后创建。工程节点可用 milestone/<名称> 标签，明确它不是产品发布。
- 不移动已发布标签，不通过强制推送改写 main。已有历史问题通过新的明确提交修复。

不要为每次整理打标签；版本标签应对应明确可交付版本和验收结果。
