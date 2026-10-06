# 文档导航

公开文档描述项目结构、功能边界与复现方式。内部交接、个人环境调试记录和原始截图留在本机，不作为公开交付材料。

## 优先阅读

- [分层架构](ARCHITECTURE.md)
- [轻量交付范围](LIGHTWEIGHT_DELIVERY_PLAN.md)
- [RAG 设计与验证](RAG.md)
- [已知限制](KNOWN_ISSUES_PRECLINICAL.md)

## 验证

自动化测试位于 backend/src/test、frontend/tests 和 scripts。运行方式见项目首页；真实外部服务测试需要显式启用，生成报告写入构建目录，不提交运行产物。

来源同步工具另见 [knowledge-sync](../tools/knowledge-sync/README.md)，其离线同步与患者在线检索是不同执行链路。
