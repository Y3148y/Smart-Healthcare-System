# 业务知识资料

此目录集中保存本机整理的知识资产，不是应用自动加载目录。运行时从数据库读取已批准正文。

| 目录 | 用途 |
| --- | --- |
| corpus/ | 当前医学资料，按主题分类；每篇含document.zh-CN.md、provenance.json和source*.en.md |
| sources/ | 原始网页快照，按登记sourceId保存 |
| reference/ | 操作者参考资料，不发布患者检索库 |
| releases/ | 发布回执、版本关联与正文/片段指纹 |
| history/ | 历史稿及旧元数据，不作为当前编辑入口 |

正文、快照、回执和历史默认Git忽略；本说明和工具可公开提交。不得把第三方全文或患者信息推到公开仓库。

管理员在医学知识库同时选择一篇资料的正文MD、provenance.json和全部来源快照，核验后提交待审；普通TXT/MD需填写来源及许可。另行审批后才进入检索，不自动读取该目录。上传附件统一归档仍未实现，现有接口只保存正文、基础来源和适用元数据，不能声称已保存全部源附件。

Markdown头记录编写时的待审状态；实际审批看数据库及releases/回执。操作者批准不等于临床审核。

离线校验：`node tools/knowledge-sync/validate-document.mjs knowledge/corpus/neurology/migraine/document.zh-CN.md`。
真实片段检查：JDK17编译backend后，运行 `java --class-path backend/target/classes tools/knowledge-sync/ChunkInspection.java knowledge/corpus`。

日志与工程评估放在本机reports/；当前开发方案与安全缺口仍在docs/，旧交接归档docs/archive/。不在业务目录保存一次性发布脚本或构建副本。

reports/rag/保存检索评估，reports/knowledge-sync/保存采集报告、intake待审包及proposals工作单。旧验证副本归档reports/archive/，不会自动参与构建或业务。
