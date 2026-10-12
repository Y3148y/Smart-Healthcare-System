# 业务知识资料

此目录集中保存知识资产，不是应用自动加载目录。运行时从数据库读取已批准正文。`corpus/` 的11篇示例资料及必要来源文本随仓库公开，可复现来源校验、待审导入和离线切分；当前全部 `permissionStatus=pending`，不能直接审批发布或作为患者端索引语料。具体转载条款和使用边界见 [LICENSE.md](LICENSE.md)。

| 目录 | 用途 |
| --- | --- |
| corpus/ | 当前医学资料，按主题分类；每篇含document.zh-CN.md、provenance.json和source*.en.md |
| sources/ | 原始网页快照，按登记sourceId保存 |
| reference/ | 操作者参考资料，不发布患者检索库 |
| releases/ | 发布回执、版本关联与正文/片段指纹 |
| history/ | 历史稿及旧元数据，不作为当前编辑入口 |

`corpus/` 仅允许正文、provenance.json、source.en.md和source.nlm.en.md四类文本文件进入Git。原始网页缓存、发布回执、intake-packet.json、历史和报告仍忽略；不上传患者数据或非许可第三方内容。

管理员在医学知识库同时选择一篇资料的正文MD、provenance.json和全部来源快照，核验后提交待审；普通TXT/MD需填写来源及许可。仅在使用权限核查完成、待审元数据明确为 `permitted` 后，授权审批才可使其进入检索；公开副本的转载许可不自动完成这一步。应用不会自动读取该目录。上传附件统一归档仍未实现，现有接口只保存正文、基础来源和适用元数据，不能声称已保存全部源附件。

Markdown头记录编写时的待审状态；实际审批看数据库及releases/回执。操作者批准不等于临床审核。

目录按主题组织：allergy、cardiovascular、digestive、gynaecology、neurology、orthopaedics、respiratory。每篇的JSON front matter通过SHA-256绑定provenance.json，后者记录来源、日期、适用范围、章节来源和快照哈希。文件使用Git二进制式换行保留规则，避免checkout换行转换破坏绑定。正文和元数据保持原稿，公开提交不自动解除原有审核阻断。

离线校验：`node tools/knowledge-sync/validate-document.mjs knowledge/corpus/neurology/migraine/document.zh-CN.md`。
真实片段检查：JDK17编译backend后，运行 `java --class-path backend/target/classes tools/knowledge-sync/ChunkInspection.java knowledge/corpus`。

日志与工程评估放在本机reports/；当前开发方案与安全缺口仍在docs/，旧交接归档docs/archive/。不在业务目录保存一次性发布脚本或构建副本。

reports/rag/保存检索评估，reports/knowledge-sync/保存采集报告、intake待审包及proposals工作单。旧验证副本归档reports/archive/，不会自动参与构建或业务。
