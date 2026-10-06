# 真实模型连通性记录

- 验证日期：2026-09-25（UTC+08:00）
- 模式：OpenAI 兼容模式
- 模型：`qwen3.7-flash-2026-07-15`
- 兼容接口：DashScope `https://dashscope.aliyuncs.com/compatible-mode/v1`
- 密钥：未写入本文件、源码、`application.yml` 或版本库；仅作为启动后端进程的临时环境变量使用。

## 验证结果

1. 对兼容 Chat Completions 接口发送最小请求，模型成功返回“模型连接成功”。
2. 以“连续三天咳嗽、咳痰、胸闷，没有发热”为输入调用实际分诊接口。
3. 安全规则和结构化分诊仍由后端控制：结果为“普通 / 呼吸内科”。
4. `summary` 返回了模型生成的两句解释，不等于演示模式的固定兜底文案，证明该请求已使用真实模型。

## 当前模型在链路中的职责

真实模型当前仅用于补充普通症状分诊的可读解释。风险等级、科室、医生、号源和红旗症状拦截仍由后端规则与工具结果决定；紧急症状不会让模型覆盖安全结论。

## 后续启动方式（不记录真实密钥）

```powershell
$env:AI_MODE='openai-compatible'
$env:AI_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:AI_API_KEY='从安全的密钥管理系统读取'
$env:AI_MODEL='qwen3.7-flash-2026-07-15'
```

生产部署应使用系统密钥库、CI/CD Secret 或容器编排的 Secret 注入，禁止把密钥写进 Git、`application.yml`、前端代码或测试记录。
