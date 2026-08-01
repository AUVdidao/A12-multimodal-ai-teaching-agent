# AI Workflow Gateway Contract

## Provider

`A12_AI_PROVIDER` 支持：

- `KIMI`：Spring Boot 直接调用 Kimi 完成结构化工作流
- `MOCK`：完全离线的确定性演示实现

`A12_AI_FALLBACK_TO_MOCK=true` 时，Kimi 未配置、超时、网络失败、返回非法 JSON或不符合 DTO 契约时，路由器可降级到 Mock。

## 状态接口

`GET /api/ai-workflow/status`

```json
{
  "requestedProvider": "KIMI",
  "activeProvider": "KIMI",
  "mockEnabled": true,
  "providerConfigured": true,
  "fallbackToMock": true,
  "message": "Kimi structured workflow routing is requested..."
}
```

状态接口不返回 API Key。

## 工作流

- `WF-01` 需求澄清
- `WF-02` 需求摘要
- `WF-03` 资料分析
- `WF-04` 本地候选知识筛选与教学意图
- `WF-05` 生成方案
- `WF-06` PPT、DOCX、互动内容结构化草稿
- `WF-07` 成果修改

所有模型调用由后端构造受控输入，要求返回单一 JSON 对象。后端负责：

1. 输入长度限制
2. API 超时与有限重试
3. JSON 代码围栏和 reasoning 标签清理
4. 一次 JSON 修复调用
5. DTO 映射
6. 业务字段完整性校验
7. Mock 降级或明确失败

## 事实约束

模型不得补造上传资料、学生数据、审批状态或检索来源。`knowledge-retrieval` 只能从 `candidateSnippets` 中选择；候选为空时直接返回空结果。
