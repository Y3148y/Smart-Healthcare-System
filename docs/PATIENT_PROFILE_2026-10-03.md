# 患者本人基础资料

## 范围与限制

患者导航新增“我的资料”：称呼必填；出生日期、过敏史、当前用药、既往健康情况选填。未知可留空，不默认为“无”。保存必须确认本人自述，来源为 `PATIENT_SELF_REPORT`，不是医院病历，不生成诊断画像。出生日期目前限制18至120周岁，不代表医院核验，也不替代问诊资格检查。

本批不修改医学规则、不添加逐症状映射、不调用外部模型；资料尚未自动进入 LLM 上下文。管理员不能通过该接口读取患者资料，人工导诊按申请授权的摘要另批实现。登录仍为本地演示账号认证；不包含正式身份核验、加密存储、撤回删除或完整隐私合规流程。

## 代码与接口

- `patient/api/PatientProfileController.own/save`：`GET/PUT /api/patient/profile`，仅 PATIENT，主体取JWT，无请求患者ID参数；错误返回message。
- `patient/application/PatientProfileService.own/saveOwn`：自述确认、日期/长度校验；空资料版本0；旧版本保存409。
- `patient/domain/PatientProfile/PatientProfileStore`：资料与存储边界。
- `patient/infrastructure/mybatis/PatientProfileMapper/MybatisPatientProfileStore`：独立`patient_profile`表持久化；更新按版本比较；初次并发插入由主键冲突拒绝覆盖。
- `schema.sql`：增加资料表，保留旧预约和会话数据。
- `frontend/src/features/profile/ProfilePage.vue`：加载/失败/保存反馈，版本冲突保留草稿、停止再次保存，用户可明确重新加载放弃本页修改；沿用蓝白风格并补焦点、小屏布局，不增加遮挡弹层。
- `frontend/src/App.vue`：患者统一导航和认证完成后挂载页面。

## 验证记录

2026-10-03，隔离副本 `.codex-profile-tests`，JDK17，无AI环境变量：`mvn clean test -q -DforkCount=0`，122项，121通过、1外部用例跳过、零失败/错误。患者新测试4项：本人权限隔离、持久化与自述来源、输入校验、并发旧版本冲突。副本含此前未提交安全修正，不将其混入本任务提交。

`.codex-rag-ui` 执行 `npm run build` 通过。当前8081后端已更新，沿用旧数据库及真实模型配置，不重建数据。`scripts/test-patient-profile-ui.cjs` 对5188真实接口验证保存、刷新读取、两页旧版本冲突、草稿保留、确认必需、管理员403。脚本使用lisi演示账号，合成健康字段在finally恢复；原称呼为空时保留“李四”与空健康字段，版本和保存时间会变化。不将测试资料当作真实病例。

本批未复验LLM回答质量，不以资料测试替代AI验收。独立复核待其他owner进行。下一批为人工导诊申请状态与必要摘要权限。
