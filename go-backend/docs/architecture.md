# LessonForge Go Backend 架构说明

版本：2026-09-02

本目录是 LessonForge Electron/Vue 教师端使用的独立 Mission-first 后端候选实现。它不替换现有 PPT Engine 或 RAG 的内部实现，也不修改现有 Java、前端或 Engine 工作树；Go 服务只通过 HTTP/文件桥接适配器访问它们。

## 1. 运行边界

```text
Electron/Vue
    │ REST + Cookie/Bearer + SSE
    ▼
LessonForge Go Backend
    ├─ Auth / owner isolation
    ├─ Mission / message / file / question state
    ├─ Agent worker + leased DB queue
    ├─ Parser adapter + PENDING/READY/FAILED worker
    ├─ ModelConnection + OpenAI-compatible ModelClient
    ├─ Planning draft / locked specification
    ├─ GenerationJob worker
    ├─ RAG adapter ─────────► existing Java RAG service (project/material mapping)
    ├─ Capability adapter ───► existing template capability service (optional)
    └─ PPT Engine adapter ───► existing PPT Engine v2 + shared artifact root
```

当下未把可选上游服务伪造成成功：RAG、模板能力、外部模型和 PPT Engine 只有在配置并真实可达时才会执行；未配置时返回明确的 `*_NOT_CONFIGURED` 或失败状态。RAG 只通过 Go 的 Java 项目/材料适配器调用既有 `/api/projects/{ragProjectId}/knowledge/search` 契约，并通过有界 locator 的 `/knowledge/materials/{materialId}/read` 读取原文；Mission 与 Java Project、MissionFile 与 Java Material 的数字 ID 必须由真实 Java 响应写入 `rag_resource_bindings`，绝不按本地 ID 推断。历史 `/api/ai-workflow/knowledge-retrieval` Workflow/Kimi 入口保持阻断。

## 2. 核心实体与所有权

PostgreSQL 初始迁移 `migrations/001_init.sql` 创建以下表：

- `users`、`sessions`：账户、不可逆 session token hash；
- `model_connections`：教师拥有的连接元数据和 AES-GCM 密文；
- `missions`、`mission_messages`：任务和服务器持久化消息；
- `file_objects`、`uploads`、`mission_files`：临时上传、文件对象和 Mission 绑定；
- `agent_runs`、`questions`、`question_answers`：可取消的 Agent 运行与交互问题；
- `planning_drafts`、`locked_specifications`：版本化计划草稿和不可变锁定规格；
- `generation_jobs`、`artifacts`：异步生成任务和最终文件索引；
- `activity_events`、`execution_audits`：会话事件流和脱敏模型调用审计。

所有读取、修改、验证、选择和执行均从当前认证用户开始做 owner 条件校验。Connection、Mission、Question、Draft、Specification、Job、Artifact 的 ID 不能单独授权跨用户访问。

## 3. 首次 Mission 原子边界

`POST /api/missions` 在一个数据库事务内完成：

1. 校验首条教师消息；
2. 如果传入 `modelConnectionId`，校验连接属于当前教师、已启用且为 `VERIFIED`；
3. 将同一教师的临时 upload 绑定到 Mission；
4. 写入首条 `USER` 消息；
5. 创建首个 `AgentRun`；
6. 写入 `MISSION_CREATED` 和 `MESSAGE_CREATED` 活动事件。

带已验证连接且所有 Mission 文件为 `READY` 时运行状态为 `QUEUED`；文件仍在解析、解析失败或没有连接时为 `WAITING_INPUTS`。配置外部解析服务后，Parser Adapter 按现有 Parser 的 multipart 契约发送 `file` 与 `fileType`，接收并校验摘要、关键词、教学阶段和正文等解析结果；Go 后端在同一事务中持久化 `mission_file_parse_results`，然后才推进内部 `parse_status` 从 `PENDING` 到 `READY`。这里的 `READY` 是 Go 后端内部文件状态，不是外部 Parser 的响应字段；未配置时保持 `PENDING`，不冒充真实解析成功。不会因为服务器环境存在某个 Provider key 而替换教师连接。

## 4. ModelConnection 与统一调用

V1 只支持 `OPENAI_COMPATIBLE`。业务层只传递：

```text
actorUserId / modelConnectionId / purpose
```

`model.Client` 负责：

- 去除重复的 `/v1` 与 `/chat/completions`，拼接唯一的 `/chat/completions`；
- 要求外部 HTTPS URL；DNS 解析后阻止 loopback、私有、link-local、unspecified、multicast 等地址；
- 限制重定向次数；每一跳重新校验 DNS；只允许初始 authority（scheme/host/effective port）不变的重定向；
- 发送 Bearer、`model`、`messages`、通用生成参数和工具定义；
- 设置超时和响应大小上限；把 Provider 错误转换为脱敏安全码；
- 只从当前 owner 的已验证 Connection 解密 API key。

密钥使用 `crypto.Service` 的 AES-GCM 格式保存；数据库只保存密文和 `key_hint`。AgentRun 在创建时冻结 protocol、base URL、model ID、密文快照和 key hint；等待期间只推进状态，不再从 Mission 当前连接重写快照。教师之后选择连接时，必要时旧的无连接 Run 以明确的取消/替代状态结束并创建新 Run。运行时按快照解密，同时仍核验当前用户对连接资源的所有权。REST/SSE 隐藏整个执行快照，不返回 API key、Authorization 或密文。

验证连接会真实发送一条最小 Chat Completions 请求并持久化 `VERIFIED` 或 `INVALID`。未配置真实 Provider 时只能保留 `UNVERIFIED`，不能把 URL 格式检查报告为验证成功。

## 5. Agent / Planning 状态

Agent worker 从 `agent_runs` 队列领取运行，允许取消并在进程重启后重新处理未完成队列。它加载当前教师可见的消息和 Mission 文件，允许的工具为：

- `search_materials`（命中内容随结果返回）
- `get_template_capability`
- `get_current_plan`

AgentRun 和 GenerationJob 都有 lease owner/token、过期时间和 heartbeat。领取使用 PostgreSQL 行锁；heartbeat 按 lease 的三分之一周期续租；完成、失败、取消必须匹配当前执行 token，过期 lease 可由新 worker 安全回收，旧 worker 的完成写入会被 fence 拒绝。启动恢复只处理已过期的 RUNNING 记录。

模型输出只接受 `MESSAGE`、`QUESTION`、`PLAN_DRAFT` 三类业务结果；非 JSON、未知字段、非法问题和不符合语义结构的计划均失败。固定问题不写入后端；结构化计划只表达教学语义、顺序、页数和素材意图，不包含 Shape、坐标、OOXML 或 Engine 内部对象。Agent 工具执行前由 Go Store 强制 Mission/owner/role/provenance/READY 文件范围。

`PLAN_DRAFT` 由服务端按版本保存。`POST /api/planning/{draftId}/approve` 生成不可变 `locked_specifications` 记录；锁定规格不能被 Engine、模型或模板适配器擅自改写、删页、拆页、合页或更换要求。

`POST /api/planning/{draftId}/approve` 在一个数据库事务内完成 owner/草稿读取、语义 Compiler、模板绑定、LockedSpecification 和 Activity；Approve 当前不会创建 GenerationJob，Generation 是后续明确分离的边界。模板绑定必须来自已配置 Capability/Profile Adapter 的完整模板身份，并与当前 READY TEMPLATE 文件一致。`MATERIAL`/`TEACHER` sourceRef 必须命中当前 owner Mission 下的 READY 文件和对应 provenance。`migrations/002_hardening.sql` 对 draft/specification 建唯一约束，重复批准返回既有结果。LockedSpecification 不提供 UPDATE 路径，并由数据库不可变触发器拒绝直接更新/删除。

## 6. GenerationJob 与 PPT Engine

GenerationJob/PPT Engine 是 LockedSpecification 之后的独立阶段，本上游验收不自动进入该阶段。未来启用时，worker 才会调用配置的 PPT Engine v2 `/internal/v1/compose-plan`，再将 Engine 原样返回的 `plan` 送入 `/internal/v2/execute`；Go 后端不实现 Shape、Group、Slot、Renderer、COM、OOXML 或页面布局。

Engine 返回的 ArtifactReceipt 使用其真实字段：`storageKey`、`fileSize`、`mediaType`、`sha256` 等。由于现有 Engine 没有通用 artifact 下载 HTTP 端点，Go worker 要求部署方配置显式共享只读 artifact root，然后：

1. 将 receipt 的相对 storage key 解析到共享 root；
2. 拒绝绝对路径、路径逃逸、最终符号链接逃逸、空文件和超过上限的文件；
3. 校验实际 size 与 SHA-256；
4. 将校验后的字节保存到 Go storage；
5. 创建教师可见 `Artifact` 并完成 Job。

生成请求默认采用 `AUTO` 回退策略：如果锁定规格绑定的教师模板没有可执行的 Engine Profile，worker 会使用系统自有的默认 PPTX/版式继续生成，并在 GenerationJob 的 `generation_mode` 与 `fallback_reasons` 中留下审计记录；如果锁定规格没有外部 `MATERIAL`/`TEACHER` 来源，则只依据锁定语义规格生成，不伪造资料来源。严格审计场景仍可传入 `STRICT`，此时缺少可执行模板 Profile 会明确失败。无论哪种模式，receipt 都必须通过共享目录、路径逃逸、文件大小和 SHA-256 校验，绝不创建假的 PPTX 或成功 Artifact。Engine payload 由 Go Compiler/worker 从锁定语义合同和实际采用的模板绑定组装，不接受模型注入的 `trustedEngineRequest`。

## 7. SSE 与持久化

`GET /api/missions/{id}/events` 返回 `text/event-stream`，事件来自 `activity_events` 数据库记录；连接建立时发送历史事件并推进游标，随后周期查询新事件并 flush。客户端可先读取历史事件再等待新事件。事件 payload 不包含密钥或 Authorization。

消息、Mission 文件绑定、问题、草稿、锁定规格、GenerationJob、Artifact 都持久化在 PostgreSQL；文件内容保存于明确的 storage root，数据库保存元数据、SHA-256 和 storage key。

## 8. 安全与失败策略

- 用户身份通过 opaque session token 访问；数据库只存 token hash；
- 资源均做 owner check；不以路径 ID 作为授权依据；
- 普通教师没有 Connection 时明确失败，即使进程环境有 `MOONSHOT_API_KEY`、`KIMI_API_KEY` 或其他 key；
- 不在代码中读取全局 Provider key fallback；
- CORS 只允许配置的 Origin，默认包含 Electron `null` 与本地开发前端地址；
- 上传、外部模型响应和 Engine receipt 均有大小限制；
- 对外错误码可诊断但不泄露密钥、Authorization、内网地址详情或完整上游错误体。

## 9. 当前验证边界

已在 Docker Bookworm 中完成 Go 普通测试、race 测试、gofmt/vet、构建和一次隔离 PostgreSQL/HTTP 核对；已验证账户、角色边界、owner 隔离、Mission WAITING_INPUTS、migration 版本、重启后健康状态与数据库持久化。Alpine race 因镜像无 gcc 保留环境边界。

上游验证证据分层记录在独立运行报告中：已有隔离环境取得真实 Java RAG Search/Read、真实教师 BYOK Test Connection，以及真实 `QUESTION → Answer → 新 AgentRun → PLAN_DRAFT` 证据；这些证据不等于当前部署环境永久保留凭据，也不自动覆盖模板 Capability、Approve→Locked 的完整教师业务链。真实 Provider 的后续复现仍需要显式教师 Connection；缺少该条件时必须标记 `LIVE_VERIFICATION_PENDING`。PPT Engine、Artifact、PPTX、Office 打开/重存/视觉检查和教师端完整联调属于本 Goal 明确不进入的下游边界，保持 `NOT_RUN/BLOCKED`。任何边界都不能用 HTTP 200、Mock、编译或单元测试替代。
