# 项目分层架构（2026-09-28）

本项目采用**按业务模块组织的分层架构**，不是完整 DDD，也不是把全部代码放在 `controller/service/mapper` 三个全局目录中的传统 MVC。每个模块按需要划分 `api`、`application`、`domain`、`infrastructure`：

```text
frontend/src/
  App.vue                         # 页面外壳、登录态及患者/管理员入口
  api.ts                          # HTTP 客户端
  features/home/HomePage.vue
  features/booking/BookingPage.vue
  features/visits/VisitsPage.vue
  features/admin/AdminPage.vue
  features/triage/
    TriagePage.vue                # 会话状态与交互
    components/AssessmentCard.vue # 每版分诊结果与版本切换
    types.ts                      # 前端分诊契约

backend/src/main/java/com/aihospital/
  identity/                        # 演示登录
  triage/                          # 多轮会话、分诊版本、规则与模型解释
  booking/                         # 预约资格校验、幂等与原子扣号
  catalog/                         # 医生与号源目录
  knowledge/                       # 知识目录（当前演示实现）
  tools/                           # 工具注册（当前演示实现）
  observation/                     # 统计与调用日志
  shared/                          # API 契约、JWT、统一异常和 MyBatis 通用转换
```

后端每个模块遵循下面的依赖方向：

```text
HTTP 请求 → api → application → domain 接口
                                  ↑
                         infrastructure 实现
```

- `api`：请求校验、身份/角色边界、响应映射；不直接编写 SQL 或分诊业务决策。
- `application`：组织用例，例如发送消息、保存新分诊版本、预约资格核验、创建预约。
- `domain`：业务规则、实体记录和存储/外部能力接口，不依赖 MyBatis、HTTP 或模型 SDK。
- `infrastructure`：MyBatis 持久化、内存演示知识与工具、可选 LLM 适配。

跨模块依赖保持单向：预约可读取分诊结果和目录；分诊可读取目录、知识和工具；目录不依赖预约或分诊。分诊结果和助手回复在同一事务内保存。预约号源扣减由数据库条件更新保障，不在前端做库存判断。`shared/model/Models` 暂时保留既有 JSON 契约，以避免重构时破坏已运行的前后端接口；后续如引入正式医院数据模型，可逐个模块迁移 DTO 映射。

## 当前边界与下一步

当前的 MyBatis 负责会话、版本、预约、号源、统计的关系型数据访问。知识资料和工具注册仍是内存演示实现；`/mcp` 是演示 HTTP 工具入口，**不是**已经对接医院的独立 MCP 服务。模型解释是可选适配，缺少配置或调用失败时分诊规则仍可给出明确的演示结果。前端页面已按患者功能和管理端分别拆到 `features`，`App.vue` 保留路由入口、登录态和跨页数据加载；其中后台四个视图仍共用一个 `AdminPage.vue`，下一步可在实际交互完善时继续细分。这样描述当前实际代码边界，不把目录调整误称为完整 DDD 或生产级医院集成。

## 验证命令

后端：使用 JDK 17 执行 `mvn test`。前端：在 `frontend` 执行 `npm run build`。回归重点是登录、会话历史、多轮消息、分诊版本不可变、多科室建议、预约幂等、管理员权限和已存在的 `/api` 路径。

2026-09-28 本机回归：后端 18 项测试通过，前端类型检查与生产构建通过。重启后经 5188 前端代理实测：患者登录成功；两轮会话产生两版分诊，第一版摘要未被第二版覆盖；“手腕骨折、嗓子疼、痛经”返回骨科、呼吸内科、妇科三个候选方向；相同幂等键重复预约返回同一预约；管理员仪表盘与调用日志可读取，患者访问管理员接口返回 403。上述测试使用演示模式，未验证外部 LLM、真实医院号源或 Qdrant。
