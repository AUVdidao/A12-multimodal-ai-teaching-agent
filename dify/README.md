# A12 Dify 工作流资产包

本目录同时保存 7 个逻辑工作流契约和 5 个可部署 Dify Workflow 应用。之所以分成两层，是因为 A12 后端需要保持 `WF-01` 至 `WF-07` 的稳定业务边界，而当前 Dify Sandbox 最多只能创建 5 个应用。

当前状态：**代码与静态契约已完成，仍需在目标 Dify 工作空间逐个导入、发布并用真实 API key 验证。** 仓库不包含任何真实 key。

## 目录结构

```text
dify/
├─ apps/                         # 实际导入 Dify 的 5 个物理应用
│  ├─ APP-01-requirement-intelligence.yml
│  ├─ APP-02-material-and-intent.yml
│  ├─ APP-03-generation-plan.yml
│  ├─ APP-04-structured-content-draft.yml
│  ├─ APP-05-edit-intent.yml
│  └─ app-routing.json           # 物理应用、逻辑工作流与 operation 映射
├─ workflows/                    # 7 个逻辑工作流的独立参考 DSL
├─ contracts/                    # WF-01 至 WF-07 的请求和响应样例
└─ scripts/validate-workflows.ps1
```

Sandbox 环境只导入 `apps/APP-*.yml`。不要再把 `workflows/*.yml` 全部导入同一个工作空间，否则会再次触发 5 个应用的数量限制。

## 五应用路由

| 物理应用 | 逻辑工作流 | operation | 后端能力 |
| --- | --- | --- | --- |
| APP-01 Requirement Intelligence | WF-01、WF-02 | `clarification`、`requirement-summary` | 需求澄清、需求摘要 |
| APP-02 Material and Intent | WF-03、WF-04 | `material-analysis`、`knowledge-retrieval`、`teaching-intent` | 资料分析、知识检索、教学意图 |
| APP-03 Generation Plan | WF-05 | `generation-plan` | 教学内容生成方案 |
| APP-04 Structured Content Draft | WF-06 | `structured-content` | PPT、教案、互动内容结构化草稿 |
| APP-05 Edit Intent | WF-07 | `revision` | 修改意图解析与版本修订 |

APP-01 和 APP-02 通过后端请求信封中的 `workflowCode + operation` 进行严格路由。它们虽然共享物理 Dify 应用和 API key，但仍返回原来的 `WF-XX` 响应契约，因此前端、领域服务和持久化逻辑无需感知应用合并。

## 调用边界

```text
Vue frontend
    -> Spring Boot API
        -> AIWorkflowGatewayRouter
            -> MockAIWorkflowGateway
            -> DifyAIWorkflowGateway
                -> one of five published Dify apps
```

- 前端不直接调用 Dify，也不保存 Dify API key。
- Spring Boot 负责用户鉴权、项目上下文、请求信封、结果校验、业务持久化、审计和 Mock 回退。
- Dify 只负责模型推理和工作流编排，不是项目、资料、教学意图或成果版本的事实数据源。
- API key 只写入本机 `.env` 或部署平台 Secret；不要写入 DSL、Git、浏览器存储、日志或截图。

## 统一物理接口

5 个应用使用相同的 Dify Start/End 变量：

- Start 输入：`request_json`，类型为 paragraph；
- End 输出：`result_json`，类型为 string；
- 模型：`langgenius/openai_api_compatible/openai_api_compatible` 下的 `GPT-5.6 Sol`；
- `completion_params` 保持 `{}`，避免兼容端点拒绝环境相关参数；
- 模型必须只返回一个合法 JSON 对象，禁止 Markdown 和代码围栏。

后端发送给 Dify 的 `request_json` 形状如下：

```json
{
  "workflowCode": "WF-01",
  "traceHint": "a12-WF-01-project-78",
  "operation": "clarification",
  "input": {}
}
```

Dify HTTP 请求由后端生成：

```json
{
  "inputs": {
    "request_json": "<serialized request envelope>"
  },
  "response_mode": "blocking",
  "user": "a12-project-78"
}
```

后端只解析 Dify 响应中的 `data.outputs.result_json`，随后校验 `workflowCode`、`success`、业务 DTO 和字段约束。原始 Dify 响应不会直接透传给浏览器。

## 环境变量

从 `.env.example` 复制到本机 `.env` 后，只需要配置 5 组应用密钥：

```dotenv
AI_PROVIDER=dify
DIFY_BASE_URL=https://api.dify.ai/v1
DIFY_APP01_REQUIREMENT_API_KEY=
DIFY_APP02_MATERIAL_INTENT_API_KEY=
DIFY_APP03_GENERATION_PLAN_API_KEY=
DIFY_APP04_CONTENT_DRAFT_API_KEY=
DIFY_APP05_REVISION_API_KEY=
```

5 个 `*_WORKFLOW_ID` 是可选响应身份校验项。在发布后能稳定取得 Dify `workflow_id` 时再填写。Spring Boot 仍兼容旧的 `DIFY_WF01_*` 至 `DIFY_WF07_*` 变量，但新部署不得继续依赖旧七槽配置。

## 导入与发布顺序

1. 在 Dify 工作空间配置 OpenAI-API-compatible 提供商，并确认模型标识准确为 `GPT-5.6 Sol`。
2. 逐个导入 `dify/apps/APP-01` 至 `APP-05`，总数正好为 5。
3. 对照 `dify/apps/app-routing.json` 和 `dify/contracts/WF-XX.contract.examples.json` 做 Preview Run。
4. 每个 operation 至少验证一个成功输入、一个缺失字段输入和一个非法路由输入。
5. 发布每个应用并创建应用 API key。
6. 将 key 写入服务端 `.env`，设置 `AI_PROVIDER=dify`；初次联调保留 Mock 回退。
7. 重启后端或 Docker Compose，检查 `/api/ai-workflow/status`，再执行真实业务链路。

APP-01 和 APP-02 必须额外验证 workflowCode/operation 不匹配时返回 `success=false`，避免合并应用把一种业务输出误送到另一种业务适配器。

### 安全录入本地密钥

不要把应用 Key 粘贴到聊天、截图、日志、源码或提交文档。请在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\configure-dify.ps1
```

脚本会隐藏输入五个已发布应用的 Key，检查它们是否为互不重复的 Dify `app-` Key，将其写入 Git 已忽略的 `.env`，并设置 `AI_PROVIDER=dify`。脚本不会输出 Key 内容。

可执行以下命令进行不暴露密钥的本地检查：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\configure-dify.ps1 -CheckOnly
```

Docker Desktop 启动后，重新创建服务，使后端获得新环境变量：

```powershell
docker compose up --build -d
```

## 静态校验

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\dify\scripts\validate-workflows.ps1
```

校验器检查：

- 7 个逻辑 DSL、7 份契约样例和公共响应 Schema；
- 5 个可部署应用与 `app-routing.json` 的一一对应；
- 7 个逻辑工作流是否且仅被路由一次；
- operation 是否出现在对应应用 Prompt 中；
- 模型、Provider、空 completion 参数、JSON-only 约束；
- 常见 API key 形态是否意外进入资产目录。

静态校验不能替代目标 Dify 版本的真实导入、Preview Run、发布和后端 API 联调。
