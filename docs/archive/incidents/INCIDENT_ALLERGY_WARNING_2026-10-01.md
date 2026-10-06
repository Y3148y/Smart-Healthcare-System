# 疑似食物相关全身皮疹未进入结构化安全预警（2026-10-01）

## 复现与根因

演示会话 `329ea291-4b38-45e7-bb85-d3a888feb8ab` 描述食物相关不适、全身大量红肿/红点和明显瘙痒，随后要求挂号。旧实现没有覆盖“食物相关反应＋全身性皮疹”的组合规则，既没有结构化紧急结果，也没有常规科室候选；知识库未命中，最终显示 `LIVE_UNGROUNDED` 的模型文字建议，页面仍为“待补充信息”。患者无法看出普通预约被阻断的确切规则原因。

## 安全边界与修复

- [MedlinePlus 食物过敏说明](https://medlineplus.gov/ency/article/000817.htm)提示，食物后严重或全身性反应需要紧急求助；[NHS 荨麻疹说明](https://www.nhs.uk/conditions/hives/)区分了皮疹扩散时的及时就医与唇、口、咽喉、舌肿胀或呼吸困难时的立即急救。本文仅将这些公开资料用于保守的就医入口提示，不用于诊断病因。
- `TriageSafetyPolicy.assess()` 新增 `ER-ALLERGY-001`：只有检测到**肯定的食物相关反应描述**且**肯定的全身性红疹/红肿描述**同时存在时才拦截普通挂号；否认或仅局部皮疹不触发该组合规则。既有气道、呼吸、循环等危险信号规则不变。
- 命中后由服务端规则生成“紧急提示”分诊版本，不咨询模型决定风险，也不返回普通医生号源；前端已有的红色警示卡、急救电话和人工复核入口负责展示。旧会话不会在读取时被自动重算，患者追加新消息后才按新规则评估。
- 本次没有新增药物建议、自动诊断或真实医院预约。规则为保守演示策略，尚未经过临床审核；真实部署需要临床团队核准触发条件、地域急救文案和人工兜底流程。

## 验证

JDK 17、清除 `AI_*` 后运行 `backend/mvn test`：48 项通过、0 失败。新增测试覆盖命中后 `SAFETY_RULE`、无普通医生、预约接口返回 409，以及“不是食物过敏”或“没有全身红疹”的反例。`frontend/npm run build`：通过。

上述数字已于提交后独立复跑确认：`backend/mvn test` 48 项通过、0 失败（Clear `AI_*`，JDK 17+ 运行时）。

补充：本机 `JAVA_HOME` 默认指向 JDK 8，直接 `mvn test` 会因 class file 版本不匹配失败；`D:\FinallShell\finalshell\jre`（JDK 17）缺少 `com.sun.net.httpserver`，会导致 `QdrantSemanticIndexTest` 发现阶段 `NoClassDefFoundError`。可用的完整 JDK 为 `D:\Elasticsearch\elasticsearch-9.3.3\jdk`。
