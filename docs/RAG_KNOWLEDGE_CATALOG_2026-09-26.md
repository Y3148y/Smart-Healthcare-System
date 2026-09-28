# RAG 医学知识来源目录

- 整理日期：2026-09-26
- 存放目录：`backend/src/main/resources/knowledge`
- 使用边界：辅助急诊风险筛查、就诊科室匹配和知识依据展示；不用于诊断、处方或治疗决策

## 资料清单

| 本地资料 | 主题 | 权威来源 | 导诊用途 |
| --- | --- | --- | --- |
| `01-emergency-triage.md` | 急诊分级、红旗症状 | WHO Interagency Integrated Triage Tool；北京市卫健委急诊预检分诊方案 | 严重呼吸困难、意识障碍、高危胸痛等情况阻断普通挂号 |
| `02-chest-pain.md` | 急性胸痛 | 国家卫健委新闻发布会；WHO Heart attack | 胸痛伴大汗、呼吸困难等症状触发急诊提示 |
| `03-stroke-warning.md` | 脑卒中识别 | 国家疾控局“中风120”科普 | 面部不对称、单侧肢体无力、言语障碍触发急诊提示 |
| `04-respiratory-clinic.md` | 呼吸科门诊分流 | 北京协和医院呼吸内科；WHO Clinical Care Pathway | 稳定咳嗽、咳痰、喘息、胸闷推荐呼吸内科 |
| `05-digestive-clinic.md` | 消化科门诊分流 | 北京协和医院消化内科；北京市卫健委急诊分级 | 反酸、腹痛、恶心等稳定症状推荐消化内科，并识别消化道出血等风险 |
| `06-basic-emergency-care.md` | 基础急救评估 | WHO/ICRC Basic Emergency Care | 支撑气道、呼吸、循环、意识改变等安全规则 |
| `07-dizziness.md` | 头晕、眩晕伴恶心 | 北京协和医院头晕科普；国家卫健委卒中警示 | 针对“头晕想吐”追问起病方式及危险信号，辅助神经内科初诊分流 |

## 来源链接

- WHO IITT：https://www.who.int/tools/triage
- WHO Basic Emergency Care：https://www.who.int/publications/i/item/basic-emergency-care-approach-to-the-acutely-ill-and-injured
- WHO Clinical Care Pathway：https://www.who.int/tools/covid-19-clinical-care-pathway
- WHO Heart attack：https://www.who.int/news-room/fact-sheets/detail/heart-attack
- 国家卫健委胸痛科普：https://www.nhc.gov.cn/xcs/c100122/202411/81a60171b43d43ff98cc6110d65a4136.shtml
- 国家疾控局卒中识别：https://www.ndcpa.gov.cn/jbkzzx/c100009/common/content/content_1838095804822040576.html
- 北京市卫健委急诊分级：https://wjw.beijing.gov.cn/zwgk_20040/ylws/201912/t20191216_1242338.html
- 北京协和医院呼吸内科：https://www.pumch.cn/department_huxnk.html
- 北京协和医院消化内科：https://www.pumch.cn/department_ims/doctor/detail/4192.html
- 北京协和医院头晕科普：https://www.pumch.cn/detail/13499.html
- 国家卫健委卒中警示：https://www.nhc.gov.cn/jkj/c100063/202109/fe3b5805d4a147c799701d641a57c75e.shtml

## 整理与入库规则

1. 只使用官方机构或公立医院公开资料，不采用营销软文、论坛问答或无法追溯来源的内容。
2. 不复制整篇网页；将与导诊直接相关的信息改写为短知识片段，保留原始 URL。
3. 明确区分“普通门诊推荐”和“紧急就医阻断”，危险信号优先级高于科室推荐。
4. 启动时自动读取 Markdown；管理员上传内容仍可作为补充，但官方资料在同等相关度下优先。
5. 查询中的否定症状不参与危险知识加权，例如“没有胸痛”不会提高胸痛急诊文档得分。

## 2026-09-26 验证结果

| 查询 | 第一命中 | 分数 |
| --- | --- | --- |
| 咳嗽胸闷挂什么科 | 呼吸系统症状门诊分流 | 0.99 |
| 反酸腹痛恶心 | 常见消化系统症状门诊分流 | 0.995 |
| 口角歪斜、肢体无力、说话不清 | 脑卒中早期识别与转运 | 0.935 |
| 剧烈胸痛伴大汗和呼吸困难 | 急性胸痛安全分流 | 0.995 |

最终普通呼吸分诊的第一条依据为北京协和医院/WHO 资料；紧急胸痛的前两条依据分别来自国家卫健委/WHO 和 WHO/北京市卫健委。
