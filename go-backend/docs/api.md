# LessonForge Go Backend API 摘要

版本：2026-09-02

除注册、登录、登出和健康检查外，接口需要当前用户认证。认证支持 session cookie 或 Bearer token；教师 Mission/Connection/Upload/Planning/Generation 资源接口要求 `TEACHER`，研究员访问这些教师资源返回 `403`，资源仍按当前用户做所有权校验。

## 1. 公共接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 服务健康状态 |
| POST | `/api/auth/register` | 创建 TEACHER 或 RESEARCHER 账户 |
| POST | `/api/auth/login` | 登录并签发 session |
| POST | `/api/auth/logout` | 撤销当前 session |
| GET | `/api/auth/me` | 返回当前用户身份 |

登录与注册错误不会返回密码或 token 原文以外的敏感信息；生产部署应通过 Electron 安全存储管理长期 session，而不是把 token 长期放入 localStorage。

## 2. Model Connection

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/model-connections` | 当前教师的连接列表，永不返回明文 key |
| GET | `/api/model-connections/{id}` | 当前教师读取连接详情 |
| POST | `/api/model-connections` | 创建 `OPENAI_COMPATIBLE` 连接 |
| PUT | `/api/model-connections/{id}` | 修改名称、Base URL、Model ID；无新 key 时保留密文 |
| DELETE | `/api/model-connections/{id}` | 删除当前教师连接 |
| POST | `/api/model-connections/{id}/enabled` | 启用或停用 |
| POST | `/api/model-connections/{id}/verify` | 真实请求 Chat Completions 验证 |

创建/修改至少需要：

```json
{
  "name": "我的模型",
  "protocol": "OPENAI_COMPATIBLE",
  "baseUrl": "https://api.example.com/v1",
  "modelId": "example-model",
  "apiKey": "provided-only-on-create-or-replacement"
}
```

响应只包含 `id`、`name`、`protocol`、规范化后的 `baseUrl`、`modelId`、`keyHint`、`enabled`、`verificationStatus`、时间字段等。状态为 `UNVERIFIED` 或 `INVALID` 的连接不能用于 Agent 或 Planning。

验证响应包含脱敏的 `safeCode`、`httpStatus`、`baseUrlHost`、`modelId` 和验证时间。可能的失败类别包括 URL/SSRF 拒绝、认证失败、模型不存在、限流、余额/配额失败、超时、网络错误和响应格式错误；不会返回完整上游错误体。

## 3. 上传与 Mission

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads` | 上传临时文件，返回 upload ID、名称、大小、SHA-256 |
| GET | `/api/missions` | 当前教师 Mission 列表 |
| POST | `/api/missions` | 以首条消息、可选临时 upload 和可选 Connection 原子创建 Mission |
| GET | `/api/missions/{id}` | Mission、消息、文件、当前草稿、Job、Artifact 聚合视图 |
| GET | `/api/missions/{id}/messages` | 消息列表 |
| POST | `/api/missions/{id}/messages` | 追加教师消息并创建 AgentRun |
| PUT | `/api/missions/{id}/model-connection` | 选择/清除当前 Mission Connection |
| POST | `/api/missions/{id}/files` | 绑定已有文件到 Mission |
| GET | `/api/missions/{id}/files` | Mission 文件列表 |

首条 Mission 示例：

```json
{
  "title": "制作物理课件",
  "description": "面向高中一年级",
  "message": "请根据上传教材制作一份关于牛顿运动定律的课件。",
  "uploadIds": ["upload-id"],
  "modelConnectionId": 12
}
```

`modelConnectionId` 不存在、跨用户、停用、未验证或无法解密时返回明确 4xx 失败，不能静默改用平台 key 或其他用户连接。省略连接时 Mission 进入 `WAITING_INPUTS`；存在连接但附件尚未解析为 `READY` 时同样等待。解析适配器完成后更新 `mission_files.parse_status`，DB worker 才把运行推进为 `QUEUED`。过期且仍为 `TEMPORARY` 的 upload 由维护 worker 删除，同时删除未绑定的 FileObject；已绑定文件不会被清理。

## 4. Agent、问题和 Planning

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/missions/{id}/questions` | Mission 问题列表 |
| POST | `/api/questions/{id}/answers` | 提交教师回答并继续 Agent |
| GET | `/api/missions/{id}/agent-runs` | AgentRun 状态 |
| POST | `/api/agent-runs/{id}/cancel` | 请求取消运行 |
| GET | `/api/missions/{id}/planning/current` | 当前计划草稿 |
| GET | `/api/missions/{id}/planning/history` | 计划版本历史 |
| POST | `/api/planning/{draftId}/approve` | 批准草稿并创建不可变锁定规格 |

AgentRun 状态包括 `QUEUED`、`WAITING_INPUTS`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`。计划草稿只有真实 Agent 输出才会产生；没有真实模型时不生成固定假成功数据。

Agent 的 `search_materials` 和 `read_material` 只有在显式配置 Java RAG、并完成 Mission→`ragProjectId` 与 MissionFile→`ragMaterialId` 的服务端映射及文件范围校验后才允许调用。`search_materials` 返回的命中只包含受 scope 过滤的来源摘要；需要有界原文时由 `read_material` 按当前 MissionFile 和 locator 再次校验后读取。历史 `/api/ai-workflow/knowledge-retrieval` Workflow/Kimi 路径保持阻断，未完成契约绑定时明确返回 RAG 未配置/不可用，不产生假检索结果。

## 5. Generation 与 Artifact

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/missions/{id}/generation-jobs` | Mission 生成任务列表 |
| GET | `/api/generation-jobs/{id}` | 生成任务详情 |
| POST | `/api/generation-jobs/{id}/cancel` | 请求取消排队或运行中的 GenerationJob |
| GET | `/api/missions/{id}/artifacts` | Mission Artifact 列表 |
| GET | `/api/artifacts/{id}/download` | 下载已校验并保存的文件 |

GenerationJob 启动时会恢复遗留 `RUNNING` 为可领取状态；取消请求持久化在数据库并在 Engine 调用前后检查。只有在 Engine 返回可校验的非空 ArtifactReceipt，并且共享 artifact root 可读取、size 与 SHA-256 一致时才会转为成功。Go 服务不生成占位 PPTX。

## 6. SSE

```http
GET /api/missions/{id}/events
Accept: text/event-stream
```

响应类型为 `text/event-stream`。连接建立时先发送历史事件并推进 `after` 游标，之后周期查询新增事件并 flush；事件形式：

```text
event: MISSION_CREATED
data: {"missionId":1,"...":"..."}

event: MESSAGE_CREATED
data: {"missionId":1,"messageId":1}

```

事件由数据库 `activity_events` 驱动，连接建立时先发送当前 Mission 的历史事件，避免第一轮轮询重复发送。事件和审计只含脱敏元数据，不含 API key、Bearer header 或密文。

## 7. 调用审计字段

每次真实模型调用记录：

```text
requestId
actorUserId
missionId
modelConnectionId
protocol
baseUrlHost
modelId
purpose
credentialSource
httpStatus
latency
timestamp
```

其中 `credentialSource` 表示当前教师的 `MODEL_CONNECTION`；本服务不支持隐藏的全局 Provider key fallback。`baseUrlHost` 只记录主机，不记录完整 URL 中可能携带的敏感信息。

## 8. 当前运行状态

开发环境中已验证 API 基础链、数据库持久化、加密密文不回显、SSE 和跨用户拒绝。真实 Provider、真实 Test Connection、Agent/Planning、RAG、PPT Engine、Office 和最终教师端联调尚未取得可用外部凭证/服务，因此必须继续标记为 `LIVE_VERIFICATION_PENDING` 或 `NOT_RUN/BLOCKED`。
