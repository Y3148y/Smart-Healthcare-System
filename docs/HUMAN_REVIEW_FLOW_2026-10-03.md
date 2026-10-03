# 人工导诊轻量闭环

## 功能与边界

患者继续在预问诊中提交申请，在“我的就诊”查看本人申请、原因和状态，可手动刷新。管理员从左侧“人工导诊申请”查看摘要、受理、关闭。状态为等待处理(PENDING)、已受理(ACCEPTED)、已关闭(CLOSED)；PENDING可受理或关闭，ACCEPTED可关闭，CLOSED不能重新打开。一会话一申请，关闭后本批不提供重开。

申请提交代表请求人工导诊处理。摘要只返回该申请关联会话的标题、最近一条患者自述（最多600字符）、最近分诊状态/科室/版本/时间与安全提示（最多600字符）。不是完整病程，不是病历，不经过LLM重新摘要。查看时读取当前版本，不是申请时快照；其他会话和个人资料不在摘要中。只接受申请ID，不接受任意会话ID作为摘要授权入口。已有管理员观测权限未在本批改造，不能据此宣称全系统已完成最小权限治理。

角色仍为演示ADMIN/PATIENT：任何管理员账号可处理申请，尚无医院专属导诊角色、分配、处理人员审计、回复意见、通知/轮询、值守排班或隐私授权撤回流程。已受理不表示医疗人员已诊疗，关闭也不表示症状解决。紧急情况不能等待申请处理；本批不更改安全规则、预约闸门、临床词表或HIS能力。

## 文件与函数级清单

- `review/domain/ReviewRecords.Summary`、`ReviewStore`：有限摘要与存储边界。
- `review/application/HumanReviewService.own/all/summary/change`：本人列表、按申请核对会话关系、限定字段、状态流转；业务离开Controller。
- `review/infrastructure/mybatis/ReviewMapper`、`MybatisReviewStore`：复用既有申请表，按患者查询；更新同时检查原状态，拒绝过期操作。
- `review/api/PatientReviewController.own`：`GET /api/patient/human-reviews`，仅PATIENT，患者主体从JWT取得，不接受目标患者参数。
- `triage/api/AdminHumanReviewController`：原GET/PATCH改为委托Service；新增 `GET /api/admin/human-reviews/{id}/summary`，仅ADMIN；未知申请404、状态冲突409，可读错误信息。
- `triage/infrastructure/mybatis/MybatisTriageStore.createHumanReview`：事务内锁关联会话，重新检查申请，重复提交返回同一条记录，避免唯一键异常。
- `frontend/features/admin/HumanReviewsPage.vue`：独立页面状态、摘要内联展示、错误/空状态、按钮请求锁、受理/关闭、刷新。
- `frontend/features/visits/ReviewStatus.vue`、`VisitsPage.vue`：本人申请状态表与手动刷新。`App.vue`：身份验证完成后才挂载就诊页。
- `HumanReviewFlowTest`、`scripts/test-human-review-ui.cjs`：权限、隔离、限定摘要、生命周期和并发重复提交验证。

## 验证

2026-10-03，`.codex-review-tests`隔离副本，先设JDK17，无AI环境变量，`mvn clean test -q -DforkCount=0`：126项，125通过、1外部测试跳过、零失败/错误。新流程测试4项通过。副本包含原来未提交安全改动，不混入本批commit。

`.codex-rag-ui`：`npm run build`通过。8081当前后端已加载，沿用原数据库、glm-5.3工作空间、百炼embedding/rerank和Qdrant配置。5188执行 `node scripts/test-human-review-ui.cjs`：lisi演示账号合成咳嗽场景真实回答，provenance.modelStatus=LIVE；提交申请→患者页面等待处理→管理员查看摘要→受理→关闭→患者刷新已关闭全部通过；zhangsan看不到申请，PATIENT访问管理员摘要403。

测试创建的合成会话与申请保留，原因标为“人工导诊流程测试-*”，结束关闭申请，不伪装成真实患者数据，不删除旧记录。模型LIVE仅证明本次调用成功，不代表临床回答质量验收。本批没新增医学判断，不能以此替代医疗安全评估。独立复核仍待其他owner完成。
