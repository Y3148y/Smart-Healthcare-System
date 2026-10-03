# 轻量改造第一批：稳定会话顺序

## 根因与修正

旧消息查询 `ORDER BY created_at,id`：H2 时间戳相同且 ID 为随机 UUID 时，用户和助手顺序可能反转。原拒答回归真实拦住该问题，不应删除或削弱。

新建 `triage_message_order` 表，保留现有消息结构。`MybatisTriageStore.appendMessage` 在事务内锁定对应 `triage_session` 行，分配下一个会话序号，并原子写入消息和序号。助手来源信息和分诊关联沿用既有事务路径。查询新消息按持久化序号排序，不修改模型文本，不伪造时间戳，也不按角色重排新多轮会话。

旧记录没有原始插入序号：保留其时间顺序，时间相同时用 USER 优先的兼容排序，再按 ID；旧数据置于新消息之前。无法从相同时间戳和随机 UUID 完整恢复旧多轮顺序，这一限制必须保留说明。新序号保证插入顺序，不保证并发 HTTP 问诊的逻辑轮次隔离，后者仍待检查。

变更：schema.sql；TriageMapper.messages/lockMessageSession/nextMessageSequence/insertMessageOrder；MybatisTriageStore.appendMessage；MessageOrderingTest。

## 验证

- 新增 3 项真实数据库测试：四条同时间戳消息仍保持 USER/ASSISTANT/USER/ASSISTANT；旧记录与新记录兼容；12 个并发写入无重复序号且全部保留。
- JDK17、无 AI 环境变量、隔离源码副本 `.codex-light-tests` 内 `mvn clean test -q -DforkCount=0`：113 项，112 通过、0 失败、0 错误、1 显式外部测试跳过。原处方拒答用例恢复通过。副本包含之前未提交的安全改动，但这些不混入本次提交。
- `.codex-rag-ui` 的 `npm run build` 通过；本批未改前端业务代码。
- 当前后端切换到 `.codex-light-tests`，仍用原 `.codex-live/data/ai-hospital` 数据库与真实聊天/检索配置。旧数据库启动建表成功，没有删除会话。
- 通过 5188 真实生产 turns 接口创建合成会话，两轮均 `LIVE`，第二轮正确记住两天病程；返回及重新 GET 会话均为 USER/ASSISTANT/USER/ASSISTANT。此测试不证明生成措辞全部符合医疗质量要求。
- 独立复核未完成；MySQL 尚未真实联调，不能把 H2 的行锁测试外推为 MySQL 已验收。

本批只修排序并更新产品说明。医生/科室/号源管理、患者基础资料、人工导诊摘要和问诊质量仍按 LIGHTWEIGHT_DELIVERY_PLAN.md 待办推进，不宣称轻量系统已全部完成。
