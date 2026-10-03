# 后台知识管理、检索调试与运行观测

Owner：GPT，用户 2026-10-03 授权。实现范围为后台页面、knowledge 管理接口、必要的管理入口认证等待及专项测试。未修改安全规则、预约语义、observation 数据库或知识源抓取清单。

## 页面与数据

| 页面 | 入口 adminPage | 内容与数据来源 |
| --- | --- | --- |
| 医学知识库 | knowledge | 真实文档列表、标题/审批筛选、正文录入、UTF-8 TXT/Markdown 上传、原文和来源、切片预览、审批与增量向量同步 |
| 检索调试 | retrieval | 使用已有 search/details 接口，展示实际模式、向量和重排状态、耗时、最终片段、每路候选排名与分数、淘汰原因 |
| AI 运行观测 | observe | 已有模型运行配置和持久化调用记录；新增检索配置、工具顺序及错误、当前进程最近 100 条检索执行摘要 |

前端拆为 KnowledgePage、RetrievalDebugPage、RuntimeObservePage，AdminPage 保留导航、首页、工具中心与人工申请。页面不显示密钥或供应商原始响应。某一观测接口失败时单独显示错误，其余接口的数据仍可展示。切换患者账号到管理员入口时，子页面等待管理员认证与初始加载完成，避免拿患者令牌提前请求而得到 403。

## 新接口

所有接口均由 RoleGuard 校验 ADMIN。

- GET `/api/admin/knowledge/{id}/details`：原文、来源、预览片段、索引写入数及状态说明。待审批资料可以预览，不进入患者召回。
- POST `/api/admin/knowledge/index/sync`：同步批准语料的增量索引；幂等 ID 已存在时不重复写入。没有实现删除集合或强制重建，界面相应称“同步向量索引”。
- GET `/api/admin/knowledge/retrieval-events`：最多 100 条执行摘要，包含时间、路径、阶段状态、候选/保留数和耗时。仅在进程内存保存，不保存患者问题和知识片段，也不是完整审计持久化。
- 既有 runtime 增加 embedding/rerank 模型名称。配置已加载和最近调用状态不等于实时健康检查。

INDEXED_IN_PROCESS 表示本进程曾成功 upsert 对应片段，不会直接探测 Qdrant 当前是否仍存在；未加载 embedding 时显示 NOT_CONFIGURED，待审批显示 NOT_APPROVED。资料与审批依旧内存演示。调用 Token 旧字段的 0 无法区分未返回和实际为 0，页面如实注明。

## 验证记录

- JDK17、无 AI_*、隔离源码副本 `.codex-admin-tests` 执行 `mvn clean test -q -DforkCount=0`。初次 110 项，109 通过、1 opt-in 外部用例跳过、0 失败；新增四项测试覆盖上传→预览→审批、向量未配置状态、管理员权限、候选诊断、100 条限长、不保存问题文本、无效格式/编码。
- 更新供应商错误码展示后的第二次全量运行，出现一项已有用例 `prescriptionRequestIsRefusedVerbatimAndOffersNoDepartmentOrBooking` 偶发失败：测试取最后消息时得到 USER。TriageMapper 当前以 `created_at,id` 排序；相同时间戳下随机 UUID 不能保证插入顺序。此次没有修改该排序代码，也没有删除或弱化用例，复验结果另记。
- 第三次全量复验仍为同一项失败，110 项中 108 通过、1 失败、1 跳过。不能将当前全量状态写成全绿。该持久化代码不在本次后台写手范围，已向用户请求单独交接修复；未获得答复前保留原代码。新增后台四项及重排响应测试均通过。
- 第三次全量复验仍为同一项失败，110 项中 108 通过、1 失败、1 跳过。不能将当前全量状态写成全绿。该持久化代码不在本次后台写手范围，已向用户请求单独交接修复；未获得答复前保留原代码。新增后台四项及重排响应测试均通过。
- 前端 `npm run build` 通过；`npm run test:auth` 为 3 通过、1 真实代理 opt-in 用例跳过。
- 运行服务使用 8081 后端和 5188 前端代理，沿用原 `.codex-live/data/ai-hospital` 数据库。以 rag-live 配置启动，密钥仅由启动进程环境提供。
- 真实 HTTP 检索中 embedding/Qdrant 返回 READY，rerank 返回 HTTP 400；直连复核供应商错误码为 Arrearage，访问被账户结算状态阻断。新增仅展示经过字符白名单过滤的错误 code，不展示 raw message/body。没有将接口故障伪装成健康状态，也未操作账户结算。
- 浏览器测试脚本为 `scripts/test-admin-ui.cjs`，需安装 Playwright 并设置 NODE_PATH。默认要求真实 HYBRID_QDRANT_RERANKED；供应商受阻场景必须显式设置 `ADMIN_TEST_EXPECT_MODE=DEPENDENCY_BLOCKED_UNRERANKED`，断言无最终证据、候选被依赖阻断、Arrearage 在页面可见。两种口径不能互称。

浏览器脚本创建的两份非医学测试资料保持待审批，不进入患者检索；重启后清空。审批功能由 MockMvc 走真实应用服务验证，测试向量接口与真实供应商应分开表述。

Chrome headless 在 1600×1000 下执行真实页面流程通过：已有患者令牌切换管理员、原文/片段查看、资料录入、文件上传、真实检索受阻结果（向量 READY、重排 Arrearage）、最近执行摘要、调用接口受控 500 时其他卡片继续显示、患者请求管理接口 403、无页面运行异常。脚本里的受控 500 仅用于浏览器失败场景，不是外部模型模拟。调用日志每页 10 条；历史成功率只计算返回记录，当前检索失败在上方单独显示。测试截图保存在未跟踪的 `.codex-admin-browser/observe.png`。

本机入口：http://127.0.0.1:5188/?demo=admin&page=admin&adminPage=knowledge；调试页为 adminPage=retrieval；观测页为 adminPage=observe。运行源码为隔离构建副本，保留原数据库；部署源还包括此前未提交的患者安全/提示修改，这些文件不会合入本次后台提交。

Chrome headless 在 1600×1000 下执行真实页面流程通过：已有患者令牌切换管理员、原文/片段查看、资料录入、文件上传、真实检索受阻结果（向量 READY、重排 Arrearage）、最近执行摘要、调用接口受控 500 时其他卡片继续显示、患者请求管理接口 403、无页面运行异常。脚本里的受控 500 仅用于浏览器失败场景，不是外部模型模拟。调用日志每页 10 条；历史成功率只计算返回记录，当前检索失败在上方单独显示。测试截图保存在未跟踪的 `.codex-admin-browser/observe.png`。

本机入口：http://127.0.0.1:5188/?demo=admin&page=admin&adminPage=knowledge；调试页为 adminPage=retrieval；观测页为 adminPage=observe。运行源码为隔离构建副本，保留原数据库；部署源还包括此前未提交的患者安全/提示修改，这些文件不会合入本次后台提交。

## 边界

本次交付不包含医学内容审批资质、知识审核状态落库、完整检索审计持久化、真实医院接入或外部供应商可用性保证。独立复核仍需 owner 以外的一方完成。
