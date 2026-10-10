# 架构与业务边界

## 组织方式

项目采用 Spring Boot 分层单体，按业务模块划分 `api → application → domain`，`infrastructure` 实现数据库和外部服务端口。部分共享契约和跨模块直接依赖仍然存在。

| 模块 | 职责 |
| --- | --- |
| identity | 本地演示登录与身份签发 |
| triage | 多轮会话、风险规则、分诊版本、模型回答 |
| knowledge | 文档持久化、切分、BM25、向量检索、融合与重排 |
| catalog | 科室、医生、排班与模拟号源 |
| booking | 预约资格、事务扣号、幂等回执 |
| patient | 本人自述资料、版本冲突控制 |
| review | 人工申请、摘要授权、访问审计 |
| observation | 调用记录、脱敏技术投影与统计 |
| tools | 本地工具执行器、管理员 MCP 子集入口 |
| shared / config | API 契约、异常、身份校验、数据库初始化 |

## 请求流程

`TriageConversationService` 读取当前问题和有限历史，按业务流程执行安全判断、知识检索、模型生成与输出检查。风险规则优先于检索和模型；急症路径直接给出安全提示并停止普通预约。普通咨询可以追问或回答一般知识，分诊推荐还需满足证据与业务条件。

工具由 Java 显式调用 `HospitalToolExecutor`。管理员认证的 `/mcp` 共用执行器，提供协议子集演示；患者流程没有通过 MCP 客户端执行工具，也没有模型自主选择工具的循环。

LangChain4j 提供消息类型、模型响应和 OpenAI-compatible 客户端。`OptionalNarrationModel` 使用消息列表构建多轮提示；显式思考/JSON 请求参数通过 `ConfiguredChatTransport` 适配。项目没有接入 Spring AI。

## 存储与一致性

应用通过 MyBatis 使用 MySQL，数据源统一配置在 `application.yml`。H2 仅用于测试；`schema.sql` 初始化表，`DatabaseIndexInitializer` 用 JDBC 元数据检查并幂等创建 trace 索引。旧 H2 数据不自动迁移。

知识正文、来源元数据、审批状态和导入幂等请求存入数据库。导入使用稳定请求键与请求哈希；同键同内容返回原记录，同键不同内容返回冲突。审批成功后发布缓存与索引状态，写入失败不发布新缓存。检索缓存是进程内状态，跨实例同步与不可变审批历史仍待补齐。

模拟预约在数据库事务内以条件更新扣号，并使用患者与请求键的唯一约束防止重复创建。相同请求重放既有回执；改变医生或会话的同键请求返回冲突。会话预约重新核验当前分诊状态、资料依据、风险和推荐医生；独立预约显式使用 `walk-in`，缺失会话不自动降级为独立预约。

新预约回执时间统一为微秒，历史回执保留快照。前端同页面的相同预约手动重试复用请求键，失败后提示先核对就诊记录；刷新后内存请求键不保留。真实 HIS 锁号、取消、对账与跨系统故障恢复尚未实现。

目录只展示启用且排班未过期的医生。服务状态分别表达科室不存在、停用、无医生、无匹配排班和无号源；启动不会自动续期历史排班或重置号源。多科室建议分别查询目录，单个查询失败不等于科室不存在。

## 生成侧安全边界

以下检查用于限制输出，不代表医学正确性已经得到证明：

| 边界 | 实现位置 |
| --- | --- |
| 风险与预约结构由后端决定 | `triage/domain/TriageSafetyPolicy`、`Disposition`、`booking/application/BookingApplicationService` |
| 资料依据状态与 `EVIDENCE_BLOCKED` | `triage/infrastructure/llm/OptionalNarrationModel`、`AnswerDraftValidator` |
| 召回/选留阈值与依赖故障 | `knowledge/infrastructure/qdrant/HybridKnowledgeCatalog` |
| `UNSAFE_OUTPUT` 检查 | `triage/infrastructure/llm/OptionalNarrationModel` |
| 药品句过滤 | `triage/infrastructure/llm/OptionalNarrationModel` |
| `SAFETY_RULE` 不受模型覆盖 | `triage/infrastructure/demo/RuleBasedTriageEngine`、`triage/application/TriageConversationService` |

患者输入和检索资料作为不可信内容注入提示。结构化回答检查本轮引用身份，`AnswerSupportReviewer` 通过一次独立模型请求核对内容支持。引用合法、核对通过与临床审核是不同事项；安全规则与中文映射未经临床审核，未命中不能排除急症。

## 可观测与权限

`TurnTraceContext` 为一轮处理分配 trace ID，消息来源、工具、生成、核对与终态日志通过 `agent_call_trace` 关联。上下文在 `finally` 释放，旧记录缺字段保留未知，不补造。生成与核对分别记录供应商返回用量；缺失用量显示未知，总耗时不能与子阶段耗时相加。这是本应用的逐轮关联，尚非分布式追踪，进程崩溃时仍可能缺日志。

普通管理员通过 `TechnicalCallView` 查看脱敏技术投影；人工队列不直接返回患者正文。摘要还需 `HUMAN_REVIEW_AUTHORIZED_SUBJECTS` 显式授权，读取与拒绝访问落库，读取审计失败时不返回正文。没有批量导出或自动清理策略。

演示 JWT 可通过 `AI_JWT_SECRET` 配置稳定密钥；未配置时进程启动随机生成，重启令牌失效。关闭演示登录不会自动接入医院 SSO。

## 前端恢复与交互

SSE 推送阶段状态，正文完成检查后整段返回。断流后有界回读已保存的问题与结果，不自动重发问诊或预约。持久化处理中与危险提示状态禁止普通重发。

会话切换和资源刷新只应用当前版本的响应；单个资源失败保留其他成功结果。患者资料成功读取版本后才能编辑，版本冲突保留修改并要求显式重载。历史分诊、采用来源与用时读取保存快照，不由当前目录或新资料覆盖。

详细检索设计见 [RAG](RAG.md)，启动与验证见 [README](../README.md)。
