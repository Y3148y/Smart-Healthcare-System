# D4 处置与预约一致性修正（2026-10-02）

仅针对演示分诊的处置矛盾，不增加临床规则。历史缺陷是低置信度结果被标为 `待补充信息`，同时附带可预约医生、会话显示 `已完成分诊`，患者仍可扣减模拟号源。

修正后的边界：`待补充信息` 不附可预约医生；若保存了该风险等级的分诊版本，会话状态仍为 `待补充信息`；预约入口即使读取到旧版本中遗留的医生，也按风险等级拒绝。没有规则科室候选时，明确要求挂号也不会仅凭鼻部症状生成全科预约。患者仍可继续提问或申请人工导诊；独立的模拟挂号页不等同于分诊推荐。

代码位置：`RuleBasedTriageEngine.needsClarification/triage/guidedFallback` 移除鼻部专用回退并限制医生附着；`TriageConversationService.send/withCurrentAvailability` 对齐状态与号源；`BookingApplicationService.book` 拒绝 `待补充信息`；`AssessmentCard.vue` 不向不可预约的旧版本或待补充版本显示预约按钮；`TriageConversationTests` 和 `DispositionConsistencyTest` 覆盖回归。

验证：JDK 17、未设置 `AI_*` 环境变量，在隔离的同源码 Maven 构建目录执行 `mvn test -q`，55 项通过、0 失败、0 错误。共享 `backend/target` 当时无法写入，未清理或覆盖。前端源码在隔离目录执行 `npm run build` 通过；共享 `frontend` 目录中 Vite 临时配置文件写入遇到 `EPERM`，未覆盖运行中的文件。独立复核尚待完成。D8 由另一位协作者处理，本次未修改 `TriageSafetyPolicy.java` 或其测试。

这仍是演示系统：未完成临床审核、真实医院号源对接或独立复核，不能据此宣称可上线。
