# 聊天业务空间地址修正与实测

## 原因与范围

同一密钥、同一 `glm-5.3`：旧 DashScope 地址返回 `Arrearage`，用户提供的北京业务空间地址返回 HTTP 200 / OK。不能据此断言用户免费额度耗尽；平台内部为何产生此差异仍未确认。

当前聊天 `AI_BASE_URL` 改为用户提供的业务空间 OpenAI-compatible 地址；embedding 和 rerank 继续使用已经实测可用的原地址。密钥只在进程环境中注入，不写入文件。

`scripts/start-rag-live.ps1` 新增 `ChatBaseUrl`，默认取进程 `AI_BASE_URL`；缺失时启动前报错，不再把 `EmbeddingBaseUrl` 自动当作聊天地址。使用示例（无密钥）：

```powershell
./scripts/start-rag-live.ps1 -ChatBaseUrl 'https://<workspace>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
```

## 当前服务验证

当前 8081 后端已重启，5188 代理保持不变。运行源码仍为 `.codex-rerank-tests` 隔离副本，保留 `.codex-live/data/ai-hospital` 原数据库及之前运行的未提交安全规则，不将这些既有改动混入本次提交。

使用真实登录接口、创建合成患者会话，再调用生产 turns 接口：

1. 「流鼻涕两天，没有发烧，想了解可以注意什么」：10767ms，`LIVE`，知识命中 2，本地工具 1，无工具错误。模型回答注意事项并追问伴随症状，没有生成预约结果。
2. 同会话「有鼻塞和打喷嚏，没有咳嗽。我前面说的症状持续多久了？」：`LIVE`，知识命中 2，工具 4，无工具错误。正确回答「持续两天」。形成分诊结果并提到呼吸内科。

第二轮科室是否正确以及「暂不急于就诊」等措辞是否有充分依据仍需检查，不能把 LIVE 状态当成医学答案正确的证明。本次没有新增症状映射、改安全规则或知识源。

## 验证与未完成项

- PowerShell 脚本语法解析通过；显式空 ChatBaseUrl 的配置拒绝通过。
- `NODE_PATH` 指向隔离 Playwright 后，`node scripts/test-admin-ui.cjs` 的 9 项检查通过，真实检索模式 `HYBRID_QDRANT_RERANKED`。
- Java 与前端代码本次未变；未重复全量构建。最近一次 JDK17 `mvn clean test` 仍是 110 项：108 通过、1 已有消息排序失败、1 外部跳过；前端隔离 `npm run build` 通过，见重排切换文档。不能报告全量全绿。
- 现有会话消息时间戳相同导致 UUID 排序不稳定的缺陷未修改。
- 独立复核与更完整的医学答案质量评估未完成。
