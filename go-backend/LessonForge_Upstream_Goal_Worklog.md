# LessonForge 上游真实闭环验收工作记录

最后更新：2026-09-08

## 工作边界

本工作记录服务于“教师创建 Mission → Locked Specification”的上游真实验收。严格停止在 Locked Specification，不调用、不修改 Generation Worker、Composer、Executor、ExecuteV2、Artifact、PPTX、Office 或 PPT Engine。

## 阶段记录

### 阶段 1：Go 后端、PostgreSQL 和源码调用链核对

- 调查内容：Go HTTP 路由、Store、Agent Runtime/Worker、RAG Adapter、Parser Adapter、Model Connection、Planning Draft、Locked Specification、SSE 和 migrations。
- 实际发现：Go Mission-first 持久化、AgentRun、Question/Answer、Draft、Locked Specification、BYOK、SSE 均有正式源码；Java RAG Adapter 通过服务端 token 和 actor header 接入，不把教师 API Key 当作 Java 服务认证。
- 关键目录：`internal/platform/httpapi`、`internal/platform/database`、`internal/agent`、`internal/rag`、`internal/parser`、`internal/model`、`internal/specification`、`migrations`。
- 结果：源码调用链已建立，不能仅凭源码把真实产品闭环标记为完成。

### 阶段 2：PostgreSQL 与 Locked Specification 边界收口

- 修改内容：`ApproveDraft` 现在只创建不可变 Locked Specification，不再在 Approve 同一事务中创建 Generation Job。
- 数据库证据：真实 PostgreSQL 集成测试确认一次 Approve 后 Locked Specification=1、Generation Job=0；重复 Approve 返回同一 Specification，仍为 0 个 Job。
- 相关报告：`D:\服务外包正式文档\开发结果汇报存档\0296_LessonForge上游LockedSpecification边界收口_开发结果汇报_2026-09-08.md`。
- 结果：上游停止线已与目标一致。

### 阶段 3：真实 Java Parser/RAG 链

- 真实材料：`D:\pri_work\tmp\pdfs\0178-r2\0178-r2-content.pdf`。
- 真实结果：Java Parser 149 页、提取文本 19,056 字符；Go `rag_resource_bindings` 已保存 Java Project/Material 映射。
- Agent 工具：真实 `search_materials` 得到 10 个结果；真实 `read_material` 返回 1,816 bytes，内容带来源文件名。
- 隔离：Mission/User scope 过滤已实测，另一用户无法访问 Go Mission、Java Project/Material 或 RAG 结果。
- 限制：该证据基于已有 Java confirmed requirement summary 的 Mission 8，不能外推为任意新 Mission 首次 Send 后都能直接进入 Java Parser。

### 阶段 4：BYOK/Model Connection 真实边界

- 真实证据：AES-GCM 密文落库、owner 隔离、服务重启后解密恢复、真实外部 Endpoint 请求、执行审计均已验证。
- 真实审计字段：actor、connection、protocol、baseUrlHost、model、credentialSource、HTTP status、latency；未输出 Key、Authorization 或数据库密文。
- Fail-closed：Go 源码没有 MOONSHOT/KIMI/OPENAI 全局 Key fallback；无可用 Connection 时 AgentRun 保持 `WAITING_INPUTS`。
- 限制：当前环境没有真实教师 Provider Key/Endpoint，因此真实 Provider 业务 Planning 仍 `LIVE_VERIFICATION_PENDING`。

### 阶段 5：New Mission、上传绑定、消息持久化

- 真实路径：真实注册/登录 → `POST /api/uploads` → `POST /api/missions` 携带 `uploadIds` → 首条消息/文件绑定/AgentRun 落库 → 追加消息创建新 AgentRun。
- 实际结果：首个 AgentRun `WAITING_INPUTS`；Mission 文件数 1；追加后消息数 2、AgentRun 数 2；另一用户读取 Mission 返回 404。
- 清理：临时教师、Mission、Session 和物理临时文件已清理。

### 阶段 6：SSE/activity_events 真实验证

- 初始事件：真实收到 `MISSION_CREATED` → `MESSAGE_CREATED`，事件 ID 单调递增。
- 实时追加：保持真实 SSE 长连接期间发送第二条教师消息，连接实时收到 `MESSAGE_CREATED`。
- cursor：断开后用 `after` 重连，能够重新读取断开点后的事件。
- 隔离：非 owner 访问同一 Mission 的 SSE 返回 404。
- 尚未完成：双用户合法并行订阅压力、异常断开后的客户端自动重连策略、高事件量 cursor 压力。

### 阶段 7：Java 需求摘要桥接边界

- 真实源码事实：Java 材料上传要求 confirmed requirement summary；Java summary generate 仍进入旧 Kimi 或显式 development Mock 路由，当前不接受 Go `modelConnectionId`。
- 禁止快捷修复：不自动调用旧 Kimi/Mock、不直接伪造 Java 数据库摘要、不绕过 Java owner/项目合同。
- 当前结论：需要 CTO 决定是否批准“Go Mission requirement context + modelConnectionId → Java confirmed requirement summary”的正式内部合同。
- 相关报告：`D:\服务外包正式文档\开发结果汇报存档\0297_LessonForge上游Java需求摘要桥接边界核对_开发结果汇报_2026-09-08.md`。

### 阶段 8：SSE 与 AgentRun 真实 API 边界补强

- SSE 真实长连接期间收到追加 `MESSAGE_CREATED`，断开后以 `after` cursor 可续接；非 owner 订阅返回 404。
- 真实 owner 取消临时 `QUEUED` AgentRun 返回 `CANCELLED`；重复取消和其他教师取消均返回 404，数据库最终状态为 `CANCELLED`。
- 取消测试的 `QUEUED` 状态由临时数据库预置，未使用 Provider/假模型输出，因此只证明 HTTP、owner 和终态门禁，不证明 Worker 运行中取消、lease reclaim 或重启恢复。
- 当前上游 Go 定向测试重跑在依赖下载/编译阶段约 90 秒无进展后安全中断；没有产生测试统计，不使用历史数字替代。
- 进一步确认本机无 Go 工具链，Docker 模块缓存为空；使用独立缓存卷重试仍停留在依赖下载，临时测试容器已清理，业务服务未受影响。

### 阶段 9：异步测试隔离与 race 收口

- 共享开发库上的失败被定位为 fixture cleanup 删除 Locked Specification 时触发不可变 trigger；没有把共享库污染当成产品失败。
- 仅修复 `internal/platform/database/store_integration_test.go` 的测试清理：对临时 fixture 精确禁用/恢复 trigger。
- 一次性 PostgreSQL 隔离库上：`go test -count=1 ./internal/agent ./internal/platform/database` exit 0；`go test -race -count=1 ./internal/agent ./internal/platform/database` exit 0。
- 当前自动化 lease/fence/reclaim/cancel/幂等证据已可复现；真实 Provider 驱动的 worker 强杀、服务重启 reclaim 和 timeout 仍未验证。

### 阶段 10：Question HTTP 门禁复验与 RAG 当前环境漂移核对

- 仅修正文档事实：`docs/architecture.md` 已与当前源码对齐，明确 `read_material` 的有界 locator 读取路径、Approve 只创建 LockedSpecification 而不创建 GenerationJob，以及下游 Generation/PPT Engine 不属于本 Goal。
- 在全新临时 PostgreSQL 上，Question/Answer HTTP 集成用例普通运行与 race 运行均通过；覆盖未回答投影、Answer 写入后的数据库重新读取、最新答案投影和跨教师 Mission 404。临时数据库已清理。
- 曾误启动包含下游下载/Generation 用例的整个 `httpapi` 包；该包的既有 fixture 清理因不可变 LockedSpecification trigger 和外键顺序失败，进程已停止，未把该结果归因于上游产品，也未继续修复下游测试。此后只运行了明确的 Question 用例。
- 0317 复核纠正了上述暂时性误判：Java Entity 使用 `@Lob String`，当前 PostgreSQL `TEXT` 列原始值是 Large Object OID；`lo_get` 解引用后为真实正文，Java/Hibernate API 也返回相同正文。对同一真实材料强制重解析后，Owner Search HTTP 200/10 hits、Read HTTP 200 且带真实来源；另一用户 Search/Read 均 HTTP 403。Go `rag_resource_bindings` 仍为 Mission 8 → Java Project 8、Mission File 4 → Java Material 11，source SHA 与真实 PDF 一致。

### 阶段 11：上游 Agent/数据库集成门禁在全新 PostgreSQL 上复跑

- 采用两个独立临时 PostgreSQL 网络，分别显式设置 `LESSONFORGE_TEST_DATABASE_URL`；测试后容器和网络均已删除。
- `go test -count=1 -timeout 180s ./internal/agent`：exit 0；Agent 数据库集成全包实际执行。
- `go test -race -count=1 -timeout 240s ./internal/agent`：exit 0；Agent 数据库集成全包 race 实际执行。
- 数据库层上游 9 个测试函数普通模式 exit 0，race 模式 exit 0；覆盖 Approve→Locked 且不创建 Generation Job、ParseResult READY 原子性、Answer 回滚、Agent output lease fence/接管/幂等/取消、Connection 停用后的旧 Run 取消与新选择。
- 本阶段明确没有运行 Generation Fence、Artifact、下载、Composer、Executor 或 PPT Engine；因此测试证据仍停在上游边界。

### 阶段 12：Generation 启动边界安全收口

- 发现原 `cmd/lessonforge-server/main.go` 无条件启动 Generation Worker，且 Agent 启动调用的旧 `RecoverInterrupted` 会更新 `generation_jobs`。
- 新增 `LESSONFORGE_ENABLE_GENERATION_WORKER`，默认 `false`；只有显式 opt-in 才构造/启动 Generation Worker/PPT Engine Client。
- `RecoverAgentRuns` 与兼容别名现在只恢复 AgentRun，不触碰 Generation 状态；未修改 Generation Worker、Composer、Executor、ExecuteV2、Artifact 或 PPT Engine 内部。
- 修复后配置/Agent 普通测试 exit 0；全新隔离 PostgreSQL Agent 普通/race 集成均 exit 0；临时资源已清理。

## 当前状态

- `VERIFIED`：Go Mission 创建、真实上传、Send 前绑定、消息/AgentRun 持久化、BYOK 加密/隔离/重启恢复/审计、SSE 初始/追加/cursor/owner 隔离、Question HTTP 投影与 Answer 后新 AgentRun 的数据库边界、Approve→Locked 不创建 Generation Job（自动化/隔离数据库证据）。
- `VERIFIED`：当前运行环境的 Java Parser/RAG 资源映射、`search_materials`/`read_material` 和当前 Project/User scope 隔离；数据库原始 OID 需按 PostgreSQL Large Object 语义解读，不能直接以 OID 字符串长度判断正文。
- `VERIFIED`（自动化数据库集成范围）：Agent Tool/输出协议运行、Question/Plan/MESSAGE 持久化、lease/output fence、Approve→Locked 数据库边界和 Connection 取消/替换门禁；本轮普通与 race 均在全新 PostgreSQL 上实际执行。
- `VERIFIED`（上游启动边界）：默认服务启动不会启动 Generation Worker，Agent 恢复只作用于 AgentRun；Generation 需要显式 opt-in。
- `PARTIALLY VERIFIED`：任意新 Mission 自动进入 Java Parser/RAG、真实 Provider 驱动的 Agent Tool Loop、完整 SSE 故障矩阵。
- `LIVE_VERIFICATION_PENDING`：当前环境重新取得真实教师 Provider 后的可复现 Agent Tool Loop，以及 Draft v1→v2→Approve→Locked 的同一真实教师业务链；0306 已提供一次真实 `QUESTION → Answer → 新 AgentRun → PLAN_DRAFT v1` 证据，但当前数据库已清理该凭据，不能冒充本轮重放。
- `NOT VERIFIED`：完整 lease expiry、worker 强杀、服务重启 reclaim、Provider/Tool timeout 的 live fault matrix。
- `NOT RUN`：Generation/PPT Engine/Artifact/PPTX/Office，按目标明确停止。

## 关键证据文件

- `D:\服务外包正式文档\开发结果汇报存档\0295_LessonForge上游真实闭环验收_开发结果汇报_2026-09-08.md`
- `D:\服务外包正式文档\开发结果汇报存档\0296_LessonForge上游LockedSpecification边界收口_开发结果汇报_2026-09-08.md`
- `D:\服务外包正式文档\开发结果汇报存档\0297_LessonForge上游Java需求摘要桥接边界核对_开发结果汇报_2026-09-08.md`
- `D:\服务外包正式文档\开发结果汇报存档\0298_LessonForge上游Mission创建绑定与SSE真实证据增量_开发结果汇报_2026-09-08.md`

## 下一步边界

在 CTO 没有批准 Java 需求摘要桥接合同前，只继续做不改变该架构边界的真实验证、测试和审计；一旦批准，仍只推进到 Locked Specification，禁止顺带进入 Generation 或 PPT Engine。

### 阶段 13：Generation 默认关闭的隔离启动实测

- 全新临时 PostgreSQL、网络和源码 Go 服务启动，显式 `LESSONFORGE_ENABLE_GENERATION_WORKER=false`。
- `/healthz` 实际 HTTP 200；启动日志没有 Generation Worker、PPT、Engine 或下游恢复日志；没有创建 Generation Job、没有调用 Engine。
- 临时服务、数据库和网络已清理，清理后无残留；共享 Bridge/Java/Engine 未触碰。
- 该证据只闭合上游验收的默认启动边界，不代表 Generation/PPTX 已验证。

### 阶段 14：真实 DeepSeek BYOK 与 Java RAG 合同阻塞重放

- 全新隔离 Go + PostgreSQL 中，正式 Model Connection 使用真实教师账户创建；`api.deepseek.com / deepseek-chat` Verify HTTP 200、`VERIFIED`，`execution_audits` 记录 `USER_BYOK`。
- 真实 Agent 首轮返回 QUESTION；教师 Answer HTTP 202，数据库写入 question answer 并创建新的 AgentRun。后续真实 Provider 请求实际发生，但结构化输出未通过严格协议，Run fail-closed 为 `AGENT_OUTPUT_PROTOCOL_INVALID`。
- 另一套全新隔离链路中，真实 PDF 上传、Go Parser READY（149 页、19056 字符）成功；真实 Agent 调用 RAG 后得到 Java 材料上传 HTTP 409：confirmed requirement summary required。只创建了 Mission→Java Project 映射，没有伪造 Material binding、Draft 或 Locked。
- 第二教师访问第一教师 Mission、Connection、Messages、AgentRuns 均 HTTP 404。真实 RAG service token 直接访问 Java `/api/projects` HTTP 200，排除 token 配置错误；当前阻塞为 Java confirmed requirement summary 内部合同。
- 本阶段未进入 Generation、PPT Engine 或下游 Artifact。
- 两套隔离 Provider 验证环境已清理，未留下临时容器、网络或标识文件；共享 Bridge/Java/Engine 未触碰。

### 阶段 15：真实 DeepSeek JSON mode 与 QUESTION→PLAN_DRAFT 重放

- 为修复真实 Provider 结构化输出漂移，`ChatRequest` 增加 opt-in `JSONMode`，Agent runtime 发送 `response_format={type:json_object}`；普通兼容请求不强制增加该字段。
- 真实 DeepSeek 对照诊断：首轮 Tool Call HTTP 200；不带 JSON mode 的对照响应可返回 Markdown 围栏，带 JSON mode 的后续响应返回合法 JSON。
- 全新隔离 PostgreSQL/源码 Go 服务中，真实教师完成注册、登录、BYOK Connection、Verify 200/VERIFIED、Mission 首轮 QUESTION；Answer 202 后第二 AgentRun COMPLETED 并落库 PLAN_DRAFT v1。
- 数据库事实：两个 AgentRun COMPLETED、question_answers=1、draft version=1/10 slides、4 条 HTTP 200 脱敏 AGENT_RUN 审计、QUESTION_CREATED 与 PLAN_DRAFT_CREATED 事件、无 Generation Job。
- 定向 model/agent 普通与 race 测试均 exit 0；隔离容器、卷、网络、临时二进制已清理。真实 RAG 材料链仍受 Java confirmed requirement summary 合同阻塞，未触碰 Generation/PPT Engine。

### 阶段 16：真实 Draft v2、Locked 与取消/重启补验

- 使用 Java 真实教师 ID 2 的 READY Template Profile 和真实 147,487,399-byte 模板文件，在隔离 Go DB 中按注册顺序取得 owner ID 2；真实模板 Parser READY、Template Capability 通过 owner/SHA/READY/EXECUTION_READY 校验。
- 真实 DeepSeek 链路产生并保留 Draft v1、v2，历史版本可读，markdown/structured plan/source AgentRun 均落库；Approve HTTP 201 写入 Locked Specification，包含 source draft/version/content hash/template binding，generation_jobs 保持 0。
- 直接修改 Locked 表被 `LOCKED_SPECIFICATION_IMMUTABLE` 数据库 trigger 拒绝，hash 未改变；说明应用幂等与数据库不可变约束共同生效。
- 第二教师访问第一教师 Mission/Connection/Draft history 404；真实运行中取消观察到 `QUEUED→RUNNING→CANCELLED` / `AGENT_CANCELLED`。
- 进程重启后 AgentRun 完成并落 Draft 的事实已取得；原脚本 stdout 丢失，kill/reclaim 只记 PARTIAL，不夸大为完整通过。
- 本阶段仍未进入 Generation、PPT Engine、Artifact、PPTX 或 Office；Java confirmed requirement summary 合同仍是 RAG live 链的硬阻塞。

### 阶段 17：真实 worker 强杀、lease expiry 与重启回收补验（2026-09-08）

- 新建隔离 PostgreSQL/网络/Go 服务，Generation Worker=false，`LESSONFORGE_JOB_LEASE=5s`，使用真实 DeepSeek BYOK。
- 真实 AgentRun 观察到 `RUNNING` 后强杀服务容器，等待 8 秒租约过期，再启动同一服务；结果为 `killObserved=true`、`statusAfterRestart=RUNNING`、`finalStatus=COMPLETED`。
- 这次把 worker 强杀后的明确 reclaim/继续执行从 PARTIAL 推进为一次真实可审计证据；临时资源全部清理。
- Provider/Tool timeout、重复 claim/idempotency 压力矩阵、高压 SSE 与 Java confirmed requirement summary 合同仍待完成；严格未进入 Generation/PPT Engine。

### 阶段 18：上游 lease、输出幂等与 Tool Loop 定向回归（2026-09-08）

- 全新临时 PostgreSQL 上运行数据库上游定向集成集合：9 个测试函数，普通模式 exit 0，race 模式 exit 0；排除 Generation Fence/Artifact/下载。
- 同一临时 PostgreSQL 上运行 Agent worker 上游定向集合：6 个测试函数，普通模式 exit 0，race 模式 exit 0。
- 证据覆盖 owner 隔离、Draft→Locked 无 Generation Job、Answer 回滚、lease expiry/takeover、输出 fencing/idempotency、连接切换取消、三类结构化输出、Tool Calling wire replay、非法输出 fail-closed。
- 未把测试桩当成真实业务通过；Java confirmed requirement summary 合同、真实材料 `search_materials/read_material`、Provider/Tool timeout、高压 SSE 仍待闭合；继续严格不进入 Generation/PPT Engine。

### 阶段 19：SSE activity cursor 与 owner 隔离补验（2026-09-08）

- 新增 `internal/platform/httpapi/events_integration_test.go`，补齐 SSE HTTP/数据库集成证据。
- 全新临时 PostgreSQL 上普通与 race 均 exit 0；验证首批事件顺序、`after` cursor 重连只返回新事件、非法 cursor 400、跨教师 Mission SSE 404 和跨 Mission 不泄露。
- 仅补测试证据，不把自动化 fixture 当作真实 Provider/RAG 通过；高压 SSE、Provider/Tool timeout、Java confirmed requirement summary 合同和真实材料 `search_materials/read_material` 仍未闭合。

### 阶段 20：真实 Go 服务 SSE live 重放（2026-09-08）

- 当前源码构建 Go 服务，连接全新隔离 PostgreSQL，通过真实 HTTP 创建 Mission 和第二条消息。
- SSE 首次收到 `MISSION_CREATED,MESSAGE_CREATED`（ID 1,2）；`after=2` 重连只收到新 `MESSAGE_CREATED`（ID 3）。
- 第二教师访问返回 404；非法 cursor 返回 400/EVENT_CURSOR_INVALID。
- 隔离服务、网络、数据库、二进制和脚本已清理；这只闭合 SSE 基础 live 证据，不代表真实 RAG/Provider timeout 已完成，也未进入 Generation/PPT Engine。

### 阶段 21：AgentRun 并发 claim 独占补验（2026-09-08）

- 新增 `TestConcurrentAgentClaimsAreExclusive`，全新临时 PostgreSQL 上两个并发 worker 竞争同一 QUEUED Run，恰好一个成功、另一个无可领取结果。
- 普通与 race 均 exit 0；临时 PostgreSQL 已清理。
- 闭合单任务重复 claim 独占不变式；高压吞吐、Provider/Tool timeout、Java confirmed requirement summary 合同和真实材料 RAG 仍待完成。

### 阶段 22：AgentRun heartbeat 与旧 lease token 栅栏补验（2026-09-08）

- 新增 `TestAgentHeartbeatRenewsLeaseAndRejectsStaleToken`，有效 token 续租推进 heartbeat/expiry，旧 token 被拒绝。
- 普通与 race 均 exit 0；临时 PostgreSQL 已清理。
- Lease/heartbeat/claim/reclaim 基础证据进一步闭合；高压吞吐、Provider/Tool timeout、Java confirmed requirement summary 合同和真实材料 RAG 仍未完成。

### 阶段 23：Provider/RAG timeout 错误边界收口（2026-09-08）

- 修复 Java RAG adapter 的 timeout 分类：超时返回 `RAG_TIMEOUT`，普通网络错误保持 `RAG_NETWORK_ERROR`，Tool Loop 不再把超时伪装成空检索。
- RAG 与 Model Transport 普通/race 测试均 exit 0，覆盖 `RAG_TIMEOUT` 和 `MODEL_TIMEOUT`。
- 仍未把受控测试当成真实故障注入；Java confirmed requirement summary 合同、真实材料 RAG 和 live timeout/压力矩阵仍待完成。

### 阶段 24：Provider 超时到 AgentRun 终态的错误语义收口（2026-09-08）

- 修复 Model Transport 返回 `MODEL_TIMEOUT` 但 Worker 只识别 `context.DeadlineExceeded` 的分类缺口：新增 `model.ErrTimeout`，Worker 现在将 Provider timeout 持久化为 `FAILED / AGENT_TIMEOUT`，其他失败仍保持 `AGENT_FAILED`。
- 新增 `TestWorkerClassifiesProviderTimeoutAsAgentTimeout`，全新临时 PostgreSQL 上普通与带 cgo 的 race 测试均 exit 0，实际经过生产 `RunOnce` 及失败落库路径；首次 Alpine race 的 cgo 环境错误未计入产品结论。
- 修复后 `./internal/model ./internal/rag ./internal/agent` 组合普通与 race 回归均 exit 0；未运行不筛选的 `go test ./...`，避免越过上游停止线。
- 临时数据库已清理。该证据是受控故障注入，不替代真实 Provider/Tool timeout live；Java confirmed requirement summary 合同、真实材料 RAG、高压 SSE 仍待完成，继续严格不进入 Generation/PPT Engine。

### 阶段 25：Go Mission → Java RequirementSummary 合同决策简报（2026-09-08）

- 只读核对确认：Java `RequirementSummaryService.generate` 通过旧 Kimi/Mock Gateway 创建 DRAFT；`confirm` 的完整字段门禁后才会置为 CONFIRMED；`MaterialService.upload` 在 `MaterialService.java:84,194` 强制依赖该 CONFIRMED summary。
- Go Mission 当前没有 Java summary 的结构化 context、stable reference/context hash 或教师确认凭证；RAG binder 只负责 Project/Material identity mapping。真实新 Mission 材料上传因此得到 HTTP409 confirmed requirement summary required。
- 已在主报告写入最小桥接合同建议：Go 拥有教师需求事实和确认，Java 以 mission/context version/hash 幂等建立/确认 summary，保留 owner/actor/服务身份/审计，不走旧 AI Gateway、不直写数据库、不猜测 CONFIRMED。
- 该合同需 CTO 冻结后才能继续真实材料 RAG；未冻结前保持 `PARTIALLY VERIFIED`，已验证的 BYOK、Question/Answer、Draft、Locked、SSE、lease/reclaim/cancel 不受影响，继续严格停在 Generation/PPT Engine 之前。

### 阶段 26：Java 需求摘要冲突的稳定诊断分类

- 仅修改 Go RAG adapter 和测试：当 Java 返回明确的 confirmed requirement summary 前置条件 409 时，统一返回 `RAG_REQUIREMENT_SUMMARY_REQUIRED`；其他 409 保持 `RAG_HTTP_409`，不携带远端正文。
- `UploadMaterial` 与通用 `javaJSON` 两条路径均有受控测试覆盖；`go test -count=1 ./internal/rag` 和 `go test -race -count=1 ./internal/rag` 均 exit 0。
- 本阶段不改变 Java 合同，不自动生成/确认 RequirementSummary，不直写库，不调用旧 Kimi/Mock，不进入 Generation/PPT Engine。
- 该改动只改善阻塞诊断；真实 Mission 材料 RAG 仍等待 CTO 冻结 Go Mission → Java RequirementSummary 合同，未把受控测试包装成 live 通过。

### 阶段 28：教师文件上传句柄释放修复

- 审查发现 `/api/uploads` 和 `/api/missions/{id}/files` 丢弃 `FormFile` 返回句柄；已在 `internal/platform/httpapi/server.go` 保留并延迟关闭句柄。
- `internal/platform/httpapi` 普通与 race 测试均 exit 0。
- 修复不改变上传、绑定、owner 或数据库语义；未启动真实 Provider/Java，不进入 Generation/PPT Engine。

### 阶段 27：最新上游组合回归

- 为核对最新 Model/RAG/Agent 变更，重跑 `go test -count=1 ./internal/model ./internal/rag ./internal/agent` 与 `go test -race -count=1 ./internal/model ./internal/rag ./internal/agent`，两者均 exit 0。
- 本轮不连接数据库、不启动服务、不执行无筛选 `go test ./...`，不把跳过的集成测试或受控测试当成 live 通过。
- Java confirmed requirement summary 合同仍是真实材料 RAG 唯一跨服务阻塞；Generation/PPT Engine 保持未进入。

### 阶段 29：过期上传物理清理可重试性修复

- 源码审查确认旧维护顺序会在物理删除失败时先删除 `uploads/file_objects`，导致孤立文件无法在下一轮重试；已将可清理对象改为“保留元数据 → 物理删除 → `FinalizeExpiredUpload` 最终化”。
- 对仍有 Mission/File/Artifact 引用的对象，过期临时上传记录可清除但文件对象继续保留；可删除对象的重复最终化安全。
- Agent 维护单元、Go 上游组合普通/race 均通过；真实 PostgreSQL 集成普通/race 均通过，首次清理后实际 `uploads=1/file_objects=1`，最终化后 `0/0`。
- 本阶段没有迁移、Provider、Java/RAG live 或服务重启新证据，严格没有进入 Generation/PPT Engine；Java RequirementSummary `CONFIRMED` 合同仍是材料 RAG 的跨服务阻塞。

### 阶段 30：上游读取入口 owner 校验复核

- 复核确认教师可读的 Messages、Questions、MissionFiles、AgentRuns、Draft、Jobs、Artifacts、SSE 和 Mission 聚合入口均以当前教师/Mission owner 为边界；Job 与单 Artifact 查询还带 SQL owner 条件。
- 先前对 Jobs 的缺口怀疑已排除；LockedSpecificationForMission 仅被内部 Generation Worker 调用，当前无教师 HTTP 读取路由。
- 本轮没有可证实的普通越权缺陷，未修改代码；Java RequirementSummary CONFIRMED 跨服务合同仍是新 Mission RAG 的阻塞，严格未进入 Generation/PPT Engine。

### 阶段 31：Go 全模块普通与 race 门禁

- 实际执行 `go test -count=1 ./...`，所有包返回 `ok`，退出码 0。
- 实际执行 `go test -race -count=1 ./...`，所有包返回 `ok`，退出码 0；使用带 cgo 工具镜像。
- 该门禁不等同于 live 业务闭环；真实新 Mission 材料 RAG 仍被 Java RequirementSummary CONFIRMED 合同阻塞，严格未进入 Generation/PPT Engine。

### 阶段 32：RequirementSummary 跨服务入口逐字段复核

- 复核 Java RequirementInput/RequirementSummary DTO、Controller、Service：现有 generate/update/confirm 不提供 Go 直接创建已确认摘要的入口，generate 必经旧 Kimi/显式 development Mock 且不接收 modelConnectionId。
- Go Mission 仍缺少结构化确认字段、Java summary stable ID、context version/hash 与教师确认凭证；不能用猜测、旧项目、直写数据库或旧 Gateway 绕过。
- 本轮未修改 Java/Go 结构或运行环境；真实材料 RAG 等待 CTO 冻结桥接合同，严格未进入 Generation/PPT Engine。

### 阶段 33：全局 Provider Key 与旧 Workflow 路径静态复核

- Go 生产源码没有读取 MOONSHOT/KIMI/OPENAI/DEEPSEEK 全局 Key；RAG 旧 `/api/ai-workflow/knowledge-retrieval` 路径明确返回 `RAG_LEGACY_WORKFLOW_PATH_BLOCKED`。
- Java 历史 Kimi Gateway 仍存在，RequirementSummary generate 仍依赖它；这是遗留边界，不是 Go 侧静默 fallback。
- 本轮未修改代码、未调用 Provider；RequirementSummary 桥接合同仍阻塞真实材料 RAG，严格未进入 Generation/PPT Engine。

### 阶段 34：Goal 完成审计矩阵

- 按 Goal 节点重新分层：Mission/上传绑定、Parser→READY、BYOK、AgentRun 生命周期、Question/Answer、Draft v1/v2、Approve→Locked、SSE 和 owner 入口已有真实或分层证据。
- 新 Mission 的 Java Material binding、带来源的真实 `search_materials/read_material` 和其双用户 RAG live 隔离仍 `PARTIALLY VERIFIED`，根因是 Java `CONFIRMED RequirementSummary` 合同未冻结。
- 全模块普通/race 已实际 exit 0，但不代替 live 业务验收；继续严格不进入 Generation/PPT Engine。

### 阶段 35：跨服务合同无新增条件复核

- Java 工作树复核为空；未发现新的 RequirementSummary bridge 实现。现有 Java generate/update/confirm 仍不接收 Go `modelConnectionId`，材料上传仍要求 CONFIRMED summary。
- Go 生产源码无全局 Provider Key fallback，旧 RAG Workflow 路径显式阻断；本轮没有可安全落地的普通缺陷修复。
- 真实材料 RAG 继续等待 CTO 冻结 Mission 需求事实到 Java DRAFT/CONFIRMED 的 `missionId + contextVersion/contextHash + owner/actor + confirmation/idempotency` 合同；冻结前不直写库、不调用旧 Kimi/Mock、不重复制造同一 409。
- 合同冻结后只执行到真实带来源 RAG/双用户隔离并回归 Locked Specification，Generation/PPT Engine 仍是硬停止线。

### 阶段 36：QUESTION 等待教师输入状态机收口

- 修复 Agent Runtime 在 QUESTION 已落库后仍由 Worker 无条件写成 COMPLETED 的缺陷；新增 `AGENT_WAITING_INPUTS` sentinel，QUESTION Run 现在持久化为 WAITING_INPUTS，lease 清除并写入等待输入活动事件。
- `QueueReadyRuns` 排除已有 QUESTION 输出的 Run，避免同一问题在文件 READY 后被重复领取；教师回答复用既有 AnswerQuestion 原子事务，创建一个新的 QUEUED AgentRun。
- 独立 PostgreSQL 上普通与 cgo race 的 Agent/Database 集成回归均 exit 0；专项测试实际观察旧 Run WAITING_INPUTS、回答后 Run=2/Answer=1/Queued=1。QUESTION stage mismatch/输出幂等测试同步通过。
- 无数据库连接的 `go test ./...` 与 `go test -race ./...` 均 exit 0；共享 Bridge DB 的一次断连测试受外部 Agent Worker 抢占，证据丢弃，专用 PostgreSQL 重跑通过。
- HTTP 下载夹具仍有既有锁定规格触发器/外键清理失败；未进入 Generation/PPT Engine，也未将该下游夹具问题扩大为当前 Goal 的开发范围。
- Java RequirementSummary bridge 和真实材料 RAG 仍等待 CTO 冻结跨服务合同；本 Goal 继续停在 Locked Specification 之前。

### 阶段 37：Java RequirementSummary 合同当前状态更正（2026-09-08）

- 阶段 35 的“Java 工作树为空”是历史快照；当前 `D:\\pri_work\\A12-ppt-stage34-integration` 工作树已存在大量既有修改和未跟踪文件，本轮未产生这些变化，也不能据此宣称 bridge 已实现。
- 当前 Java `lessonforge` 包只提供 Mission/消息/文件/Submission 接口，没有 Go→Java RequirementSummary bridge；现有 `RequirementSummary` generate/latest/update/confirm 仍由 Java 旧 AI Workflow 驱动，generate 不接收 `modelConnectionId`，材料上传仍要求 CONFIRMED summary。
- Go RAG adapter 的 Java Project/Material/Parser/Index/Knowledge Search/Read 路径未改变；真实新 Mission material binding 仍在 Java CONFIRMED summary 门禁处阻塞，没有新增带来源 RAG live 证据。
- 本阶段只读，无代码、迁移、配置、产物、Provider、Generation/PPT Engine 变更；等待 CTO 冻结 `missionId + contextVersion/contextHash + owner/actor + 教师确认 + 幂等/回滚` 跨服务合同。

### 阶段 38：CTO 新材料合同冻结后的独立 intake、Parser/RAG 真实复验（2026-09-08）

- CTO 已冻结新规则：LessonForge V1 不调用 Java `RequirementSummary.generate`，不调用旧 Kimi/development Mock；Go 持有 Mission/需求上下文/教师确认/BYOK 权威；Java LessonForge 链只提供 Parser/Material/Index/Search/Read；旧 Java public Material API 的 confirmed-summary 门禁继续保留给旧链。
- Go `internal/rag/client.go`、`internal/rag/resources.go` 和 migration `009_rag_binding_source_identity.sql` 已切换到独立 LessonForge intake、parse/index、Mission-scoped Search/Read；Java 新增 `lessonforge/material` intake/pipeline/knowledge controllers/services、`LessonForgeMaterialBinding` repository/entity 和 V19 migration。
- Go 在发起跨服务请求前校验真实 Mission owner、MissionFile、READY、角色/provenance、源文件 SHA；Java 校验 service identity、actor/owner、project owner、Mission/File identity、字节 SHA/size，并以 `missionFileId + sourceSha256` 唯一约束支持 Java 成功/Go 失败后的幂等重试；不做跨数据库分布式事务。
- 独立 Java r2（18096）健康 HTTP 200，Flyway 18 migrations validated，schema version 19；Go r4（18097）健康 HTTP 200，Generation worker 显式关闭。
- 真实 README.md 2074 bytes、SHA `91b00cb60a35ff991dee762bae059c461798f282ab9af2f630672acbff3c20a0` 通过 Java 新 intake HTTP 200，返回 binding=3/material=3/BOUND；同请求重复仍返回同一 binding/material。错误 SHA=409、错误 Mission identity=409、错误 actor/owner=403。
- 旧 Java `/api/projects/3/materials` 在无 confirmed summary 时仍 HTTP409；没有通过放宽旧接口、旧 Kimi、Mock 或直写数据库绕过。
- Go 生产 RAG client 通过新内部 Java Search/Read 得到 2 个带 `README.md` source 的真实 chunk 和真实正文；Java Material3 `PARSED/SUCCEEDED`，ParseResult 有 analysisRun/sourceVersion/checksum，8 个 knowledge chunks；Go/Java binding 对 Mission4 均保持单条 MATERIAL 映射，重复没有新增。
- Java 跨教师 Search 返回 403；Go 另一教师读取 owner=2 Mission4 返回 404。Service Token 只证明服务身份，不能代替 owner/actor 授权。
- 当前源码 `go test -count=1 ./...` 和 `CGO_ENABLED=1 go test -race -count=1 ./...` 均实际 exit 0；Java focused intake/migration/index tests 10/10 exit 0，Docker compile 361 main/84 test source exit 0。
- 旧阶段中“Java RequirementSummary CONFIRMED 是新 LessonForge RAG 唯一阻塞”现标记为 HISTORICAL：它仍是旧 public Material API 的规则，不再是新 internal intake 的规则。当前已绑定 READY 材料的 Java Parser/Index/Search/Read 为 VERIFIED；任意新 Mission 自动 Parser 调度仍 PARTIALLY VERIFIED，因本次 live 明确使用了 READY 前置。
- 当前没有真实教师 Provider/API Key，因此新合同下真实 `LLM→search_materials/read_material→LLM`、Test Connection、execution audit、timeout 和完整新链 Locked 重放仍 `LIVE_VERIFICATION_PENDING`；不要用历史 DeepSeek 或受控测试替代。
- Goal 硬停止线不变：Generation Worker、Composer、Executor、ExecuteV2、Artifact、PPTX、Office、PPT Engine 均未进入；下一条允许路径只到 Locked Specification。

### 阶段 39：Go Parser Worker 接入独立材料合同与真实 Mission/RAG 复验（2026-09-08）

- 新增 `internal/rag/lessonforge_parser.go` 生产适配器，并在 `cmd/lessonforge-server/main.go` 的 `LESSONFORGE_RAG_BASE_URL` 路径接入；它不调用旧 `RequirementSummary.generate`、旧 Kimi 或 development Mock。
- 适配器先以 Go 数据库校验 Mission owner、MissionFile/file object、size、SHA、storage key，再通过 Java LessonForge internal intake → parse → index → Go binding；RAG 失败不静默落旧链。`MissionFiles` SQL 同步修复为 join `missions` 并返回真实 owner，修复了首次 live 解析 fail-closed 的实际根因。
- 新增 Parse Result rich response 校验和 adapter 测试，覆盖 HTTP 顺序、actor、Mission/File/owner/SHA/size 及结果映射；`go test ./...`、`go test -race ./...`、Go Docker build 均 exit 0。
- 全新 a12-0319 隔离栈真实教师 user=1 创建 Mission3、首条消息、上传 `docs/api.md`（7340 bytes，SHA `e128f565fb4b017c941f9e3cf5d538c29b82e73271405eea16bd445fcd7ef955`）；Worker 自动执行 Java intake/parse/index，MissionFile READY。Java binding/material=1，parse SUCCEEDED，37 chunks；Go Search 10 个带来源命中，Read 35 bytes真实正文。
- 相同 `missionFileId+sourceSha256` intake 重试复用原 binding/material；另一教师 Mission 读取 404，错 SHA 409，错 owner/actor 与跨教师 Search 403。Service Token 不代替业务 owner/actor 校验。
- 阶段 39 只把新材料合同、自动 Parser/Index、来源 Search/Read 和跨服务幂等推到 VERIFIED；真实教师 Provider/BYOK/LLM Tool Loop、execution audit、Question/Draft/Locked 新链重放仍 `LIVE_VERIFICATION_PENDING/PARTIALLY VERIFIED`，Generation/PPT Engine 继续未进入。

### 阶段 40：真实 PostgreSQL 上游集成测试补强与下游边界核对

- 使用全新临时 PostgreSQL 16.15 和独立 network 执行 `go test -count=1 ./internal/platform/database ./internal/agent`，退出码 0；实际覆盖 Agent lease/claim/heartbeat/reclaim/cancel、QUESTION WAITING_INPUTS、输出幂等/恢复和受控 Tool Calling loop。
- 选择性运行 HTTP 上游集成测试，SSE cursor reconnect/owner isolation、Question latest answer/owner isolation、CORS 和用户侧 Generation route 边界均退出码 0。
- 完整 HTTP API 包的失败仅来自下游下载/Generation teardown：含 Locked Specification 的 Mission 删除触发 `LOCKED_SPECIFICATION_IMMUTABLE`，随后出现外键清理错误。生产不变性触发器没有被关闭；本 Goal 不修改该下游夹具，不把全包结果记为通过。
- 专用 PostgreSQL 容器与 network 已清理；没有真实 Provider/API Key，真实 `LLM→Tool→LLM`、execution audit、Question/Draft/Locked 新链复跑仍 pending；严格未进入 Generation/PPT Engine。

### 阶段 41：Agent Tool 错误脱敏收口与 Go 全量门禁复跑

- 修复 `internal/agent/runtime.go` 的 `safeToolError`，不再把底层 error 原文写入 Tool Result；仅保留白名单安全错误码，未知错误返回 `TOOL_FAILED`，Provider HTTP 状态有稳定映射。
- 新增 runtime 脱敏测试，覆盖本地路径、内部错误和 Provider 错误 body 不泄露。
- 最新普通 Go 全包与 `CGO_ENABLED=1` race 全包均 exit 0；Docker build 含测试和 server build exit 0。
- 没有真实 Provider/API Key，真实 `LLM→Tool→LLM`、execution audit 与新合同 Locked 重放仍 pending；严格未进入 Generation/PPT Engine。
### 阶段 42：冻结新材料合同后的真实 BYOK、RAG Tool Loop 与上游闭环复验（2026-09-08）

- 纠正阶段 40/41 的过时快照：本阶段隔离栈实际使用一条临时真实教师 DeepSeek Connection；所有 Key、Authorization、数据库密文均未输出。V1 不调用 Java `RequirementSummary.generate`、旧 Kimi/development Mock。
- 继续遵守冻结规则：Go 持有 Mission/需求上下文/教师确认/BYOK，Java LessonForge 链只做 Parser/Material/Index/Search/Read，旧 public Material API 的 confirmed-summary 门禁仅服务旧链；Service Token 不代替 owner/actor/Mission/File/SHA/size；`missionFileId + sourceSha256` 为幂等身份，不做分布式事务。
- Go 修改集中在 `internal/agent/runtime.go`（脱敏 `AGENT_TOOL_CALLED` 审计和稳定 Tool 错误码）与 `internal/model/client.go`（DNS 解析后直拨已校验 IP），并补相应单测；前序 `LessonForgeParserAdapter`、ParseResult 校验、MissionFile owner SQL 修复和生产接线在本次 live 栈实际生效。
- 当前 `go test -race -count=1 ./...` exit 0；普通全包和 Docker build 0325（gofmt/测试/server build）exit 0。此前错误的 login-shell PATH 调用不计入证据。
- `a12-0324-*` 隔离 live 证据：Connection 1=`OPENAI_COMPATIBLE`，最终 host=`api.deepseek.com`，model=`deepseek-chat`，`VERIFIED`/HTTP200；DB 只有 encrypted bytes=87/key hint=`****3cae`；Go 重启后仍能解密使用；重复路径输入被规范化为 `https://api.deepseek.com/v1` 后真实 Verify HTTP200；当前 execution audit=13，全部 `USER_BYOK`。
- Mission1 真实上传 `docs/api.md` 7340 bytes/SHA `e128f...7ef955`，QUESTION→Answer→question_answers→新 AgentRun→PLAN_DRAFT v1 已落库；Mission2 真实 `LLM→search_materials→read_material→LLM→PLAN_DRAFT` 完成，SSE/Activity 顺序为 Mission/message/tool/search/read/read/draft/completed，未使用 Mock。
- Java DB 的 Mission1/2 binding 各自 `BOUND`，材料 `PARSED/SUCCEEDED`，各 37 chunks；Go MissionFile=`READY`，Search/Read 返回带 `api.md` 来源的真实内容；重复 `missionFileId + sourceSha256` 重试复用 binding/material。
- 教师2访问教师1 Connection/Mission 为 404，使用外部 Connection 创建 Mission 为 400；无 Connection 的 Mission shell 仅进入 `WAITING_INPUTS`、不产生 Provider audit；Go 容器全局 Provider Key env=`ABSENT`，无静默 fallback。
- 真实 PPTX 模板 intake=`RAG_HTTP_400`，Approve=`HTTP400/REQUEST_REJECTED`，因无真实 TEMPLATE READY/Profile/Capability，LockedSpecification=`NOT VERIFIED/BLOCKED`；严格未进入 Generation/PPT Engine。
- 当前结论：Mission/消息/文件、Java 新 intake/Parser/Index/Search/Read、DeepSeek BYOK、Mission2 Tool Loop、Mission1 QUESTION→Answer→新 Run→Draft=`VERIFIED`；Draft v2 live、完整新合同 Approve→Locked、其他 Provider/timeout/high-pressure lease/SSE 仍部分或未验证；`P0=0`，`P1候选=1`（无 Connection 是否允许先建 shell需 CTO 决策），其余为 P2/验证边界。

### 阶段 43：真实 PPTX 新合同入库、RAG 文件身份错位修复与 Locked 边界复验（2026-09-08）

- 本阶段继续只做上游验收，严格停在 Locked Specification 前；未进入 Generation/PPT Engine。
- `internal/rag/client.go` 的新内部 intake 保留真实 MIME；`internal/platform/database/store.go` 的授权查询改用 `mission_files.id`，修复 `mission_file_id` 与 `file_object_id` 不一致时的跨服务身份错位；冻结的 Java Parser/Material/Index/Search/Read-only 合同、Go owner/actor/mission/file/SHA/size 校验和 `missionFileId + sourceSha256` 幂等身份不变。
- `lessonforge-0326-go:current` Docker build exit 0；修复后容器内 `go test -race -count=1 ./...` exit 0，所有 package `ok`；Java Maven 本阶段未重跑。
- 新鲜隔离栈 `a12-0325-*` 中真实 DeepSeek Connection Verify=HTTP200/VERIFIED，host=`api.deepseek.com`、model=`deepseek-chat`；encrypted bytes=87、key hint=`****3cae`；Go 重启后仍能解密，15 条 execution audit 均为 `USER_BYOK`/HTTP200且无 Key/Header。
- 真实 PPTX 模板以明确 MIME 入库并到 READY；Mission5 强制产生 `mission_file_id=6`、`file_object_id=7` 的错位，真实 AgentRun `399c399d-84db-46de-82d4-5e82d7e6ca22` 完成 search/read，最终消息带文件来源和真实 chunk 内容；Java binding=4、Search/Read HTTP200。
- Mission4 已取得 Draft v1，但真实 Approve=HTTP400/`REQUEST_REJECTED`，locked=0、generation_jobs=0；完整 `TemplateProfileResolver` 门禁要求真实合法 Profile/能力资料，当前新 Java 材料链没有满足该合同的 TEMPLATE READY/Profile/Capability。禁止伪造、放宽、直接写库或回退旧 Kimi/Mock/Renderer。
- 当前判断：材料 intake/Parser/Index/Search/Read、DeepSeek BYOK、RAG Tool Loop、QUESTION→Answer→新 Run→Draft 已有真实证据；Template Profile/Binding→Approve→Locked=`NOT VERIFIED/BLOCKED`，Draft v2/其他 Provider/timeout/lease/SSE仍未完成。下一步需要 CTO 先决定合法模板绑定/Profile来源；在决策前不进入 Generation/PPT Engine。
- 真实隔离资源收尾已完成：`a12-0325-go`、`a12-0325-java`、`a12-0325-pg` 容器，`a12-0325-net` 网络及三个 `a12-0325-*` 数据卷均已清理，核验残留数为 0。

### 阶段 44：Go 自有模板绑定后的真实上游闭环与 Locked Specification 验收（2026-09-08）

- 本阶段严格止步 Locked Specification；未进入 Generation/PPT Engine。新增 `internal/templatebinding`，生产从真实 Go TEMPLATE `mission_files` 构造 `LESSONFORGE_UPSTREAM_TEMPLATE_BINDING`，校验 owner、missionFileId、fileObjectId、size、SHA、MIME 与 OpenXML 结构；`executionReady=false`，不冒充 Engine-native Profile，也不再依赖 Java TemplateProfile/Capability。
- 冻结规则保持：不调用 Java `RequirementSummary.generate`、旧 Kimi/development Mock；Go 权威持有 Mission/需求上下文/教师确认/BYOK；Java 仅 Parser/Material/Index/Search/Read；Service Token 不代替逐项 owner/actor/mission/file/SHA/size；`missionFileId + sourceSha256` 幂等；无分布式事务。
- 自动化：Go 普通全包、`CGO_ENABLED=1 go test -race -count=1 ./...`、Docker build exit 0；Java backend/parser build exit 0、Flyway v19；Java Maven 全量未在本阶段重跑。
- `a12-0328-*` 双 PG 隔离栈健康后执行。错误的单 PG 组装仅作为环境校正，不计入业务失败；收尾后容器/网络/卷=0。
- 真实教师5/Connection4：DeepSeek Verify HTTP200/VERIFIED，host=`api.deepseek.com`、model=`deepseek-chat`；Go 容器无 Provider Key env。真实材料 474 bytes/SHA=`20aaa79e...1eff55`、真实 PPTX 198395 bytes/SHA=`5093ecbc...a0a263` 上传 HTTP201 并 READY；Java `PARSED/SUCCEEDED`，chunks=9，Go RAG binding 与来源 SHA 落库。
- 首轮 AgentRun 真实 WAITING_INPUTS/Question=`316348db-e7bc-4abb-a5ce-225d981557ca`；Answer HTTP202 创建新 Run，数据库重建上下文后 Draft v1 id=`5fb3d317-df6a-48c2-a36c-4a65ac6eaca3`、8 slides；Approve HTTP201 生成 Locked `9d76735e-2f77-4112-9808-00319e4379d2`、version=1、content hash=`305c8549...12a5ac`。直接 DB 核对 generation_jobs=0、artifacts=0。
- Activity/审计真实包含 `search_materials`、`read_material`、`get_template_capability`、`get_current_plan` 与 DeepSeek USER_BYOK；无 Mock RAG/假模型。教师6/Connection5 的 Go 重启前后 Verify 均 HTTP200/VERIFIED；DB 仅核对 owner=6、encrypted bytes=87、key hint=`****3cae`、enabled/status，不输出密文/Header。
- 当前仍是开发候选：P0=0，未新增普通 P1；Draft v2、其他 Provider、错误/429/timeout、lease/reclaim/restart/cancel、SSE cursor、Java Maven 全量及 Engine-native Profile 为 P2/未验证边界。不得因 Locked 达成而进入 Generation/PPT Engine。

### 阶段 45：上游 PostgreSQL 状态恢复与 Draft 历史证据补齐（2026-09-08）

- Go 上游增量：`CancelAgent` 纳入 `WAITING_INPUTS`；集成测试新增过期 AgentRun 恢复、Draft v1→v2 历史保留、WAITING_INPUTS cancel。
- 独立 PostgreSQL 上游定向回归：`internal/agent`、`internal/platform/database`、`internal/platform/httpapi` exit 0；三项新增用例通过；Generation fixture 未计入。
- 最新 `go test ./...`、`go test -race ./...` 和 Docker build 均 exit 0；临时容器/网络清理后为 0。
- 一次误筛的下游 Generation teardown immutable-trigger 失败不计业务证据，未改下游；严格上游筛选随后通过。
- 当前保持开发候选/P0=0；Draft v2、AgentRun 恢复和等待输入取消证据补强，长进程 restart live、其他 Provider/错误矩阵/SSE 高压/Java Maven 全量仍待验证；继续硬停止在 Locked Specification 前。

### 阶段 46：Go 服务进程重启后的 AgentRun 恢复实演（2026-09-08）

- 隔离 `a12-0332-restart-*` 中预置过期 `RUNNING` AgentRun 和 `PENDING` 材料；Go 容器重启后 health HTTP200，数据库实测 `RUNNING→WAITING_INPUTS`，lease/heartbeat/started_at 全部清空。
- `generation_jobs=0`，未调用 Provider/Generation；临时容器、network、端口已清理。
- 结合阶段45，Draft v1/v2、恢复、WAITING_INPUTS cancel 证据补齐；其他 Provider/错误矩阵/SSE 高压/Java Maven 全量仍未验证，硬停止在 Locked 前。

### 阶段 47：Locked Specification 数据库不可变性直接验收（2026-09-08）

- 全新 PostgreSQL 直接 UPDATE Locked Specification 被数据库触发器拒绝，content_hash 保持原值；Approve 幂等/无 Generation 副作用测试通过。
- 最新普通/race 全包与 Docker build exit 0；临时资源清理完成。
- P0=0，Locked 数据库不可变性证据补齐；其余 Provider/错误矩阵/SSE 高压/Java Maven 全量仍 pending，继续硬停止在 Locked 前。

### 阶段 48：真实 Provider 错误分类与 BYOK 失败闭环（2026-09-08）

- 只收口 Go Transport/API 错误分类与测试：400=`MODEL_REQUEST_REJECTED`、5xx=`MODEL_PROVIDER_ERROR`，保留 HTTP status 和稳定脱敏码，不返回 Provider 原文或敏感字段。
- 最新普通全包与 race 全包均 exit 0；Docker build `lessonforge-0337-go:current` exit 0，临时镜像已删除。
- 全新 `a12-0336-*`/`a12-0337-*` 实测：DeepSeek 错误 Key=401/`AUTHENTICATION_FAILED`；真实教师 Key=200/`VERIFIED`；错误 Model=400/`MODEL_REQUEST_REJECTED`；实际 host=`api.deepseek.com`、source=`USER_BYOK`，没有输出 Key。
- 非法 Endpoint、SSRF、跨教师 Connection 读/用均被拒绝；无 Connection shell 仅 `WAITING_INPUTS`、无 Provider audit。402/404/408/429/502 仅有单测证据。
- 本阶段仍未进入 Generation/PPT Engine；专用资源清理为0；既有“无 Connection 是否允许先建壳”产品契约候选仍待 CTO。

### 阶段 49：真实进程 SSE 首次读取、cursor 重连与跨教师隔离（2026-09-08）

- `a12-0338-sse-*` Go+PG 真实进程栈完成 SSE：首次读取 ID `[1,2]` 为 `MISSION_CREATED → MESSAGE_CREATED`；`after=2` 重连只得到新 ID `[3]` `MESSAGE_CREATED`。
- 跨教师读取同一 Mission SSE=404；无 Connection 的新消息 Run=`WAITING_INPUTS`、无 Provider audit、generation_jobs=0。
- 资源、镜像、18102端口清理为0；高压并发/断线风暴仍 pending，未进入 Generation/PPT Engine。

### 阶段 51：上游租约、幂等与 Draft/Locked 当前源码复跑（2026-09-08）

- 全新 PostgreSQL/独立网络 `a12-0340-lease-*` 上对当前 Go 源码定向跑 8 个上游 database integration tests，普通与 `-race` 均 exit 0。
- 覆盖 concurrent claim、heartbeat/stale token、expired-run recovery、output fencing/idempotency、WAITING_INPUTS cancel、Draft v1→v2 历史、Approve→Locked no Generation Job；明确未纳入 Generation/Artifact/download。
- 无 Provider 请求、无 Generation Job；临时 PostgreSQL、网络和 18103 端口已清理为0。该证据补强当前源码的数据库异步边界，但不宣称高压流量、Provider/Tool timeout 或 SSE 断线风暴通过；P0=0、无新增普通 P1。

### 阶段 50：LessonForge RAG 生产选路与旧链旁路复核（2026-09-08）

- 只读核对 `cmd/lessonforge-server/main.go`：配置 `LESSONFORGE_RAG_BASE_URL` 时只选择 `LessonForgeParserAdapter`，使用 Java 新 `/api/v1/internal/lessonforge/...` 材料合同；新 RAG 运行时错误不会落到旧 Parser/Workflow。
- 未配置新 RAG 时允许显式 `LESSONFORGE_PARSER_URL` 兼容模式，目标是独立 `file-parser-service` `/internal/file-parser/parse`。该 Java 服务实际依赖 PDFBox/POI 做确定性提取，无 Kimi、模型或 RequirementSummary；两者都为空则 `PARSER_NOT_CONFIGURED`，不存在隐式地址 fallback。
- Compose 默认同时声明新 RAG/兼容 Parser，但新 RAG 分支优先；兼容模式是部署级显式开关，不是教师请求的 Provider/Key fallback。旧 Java RequirementSummary/Kimi/public Material API 仍只属于旧链；Go 新生产 RAG 未调用，旧 Workflow path fail-closed。
- 本次没有发现需立即修改的生产旁路，因此未改代码。是否最终从生产部署移除兼容 Parser 由 CTO 决定；P0=0、无新增普通 P1。阶段 48 普通/race 全包与阶段 49 SSE live 证据保持有效，硬停止在 Locked 前。

### 阶段 52：真实 DeepSeek 客户端超时与重启后 Connection 解密（2026-09-08）

- 新鲜隔离 `a12-0342-timeout-*` Go+PostgreSQL：创建真实教师 Connection 后停止 Go 服务，再以相同 PG/加密密钥重启并执行同一 Connection 的真实 DeepSeek Verify。
- 最终请求实际使用 `api.deepseek.com` / `deepseek-chat` / `USER_BYOK`；`status=INVALID`、`safeCode=MODEL_TIMEOUT`、Provider HTTP status=0，含义是客户端 1ms 超时前未收到 Provider 响应。
- 数据库 `encrypted_api_key` 仅核对长度 87；Verify 审计 1 行，`OPENAI_COMPATIBLE`、host/model/source 均正确，未输出 Key、Authorization、密文；API 响应未返回 apiKey。
- 该证据补齐一次真实 Provider 客户端 timeout 与重启解密，不覆盖 429、网络故障、Tool timeout 或高压矩阵。a12-0342 容器/网络/镜像/18104 清理为0；未修改代码或下游。

### 阶段 53：Agent 独立 Tool Timeout 代码与回归收口（2026-09-08）

- Runtime 从只有 AgentRun 总 timeout 改为每个允许工具都有独立 deadline；配置 `LESSONFORGE_TOOL_TIMEOUT`，默认 30 秒。
- 自身 deadline 到期且父 Run 未到期时回灌固定 `{"error":"TOOL_TIMEOUT"}`；父 Run deadline 不误标记为 Tool timeout。
- `TestRunToolWithTimeoutReturnsStableCodeAndPreservesParentDeadline` PASS；普通 `go test -count=1 ./...` 和 CGO `go test -race -count=1 ./...` 均 exit 0。代码/自动化证据不等同于真实 RAG 故障注入，真实 Tool timeout、429/网络故障与高压矩阵仍 pending。

### 阶段 55：Mission Files、Planning Draft 与 Approve 的跨教师 HTTP 隔离（2026-09-08）

- 新增 `internal/platform/httpapi/upstream_isolation_integration_test.go`，只覆盖上游 Mission、Mission Files、Current/History Draft 与 Approve 的 HTTP owner 边界；不创建 Generation Job、不调用 Provider/PPT Engine。
- 全新 PostgreSQL 上两个真实教师 Session：owner A 能读自己的 File/Draft；owner B 读取 Mission、Files、Current/History Draft 均 HTTP404；owner B Approve owner A 的 Draft 返回 HTTP400/`NOT_FOUND`。
- DB 核对越权审批无副作用：`locked_specifications=0`、`generation_jobs=0`；owner 响应同时核对 missionFileId、SHA、Draft ID、owner、version、markdown。
- 普通 Go 全包、CGO race 全包、Docker build `lessonforge-0350-go:current` 均 exit0；临时 PG/容器清理完成，镜像随后删除。P0=0，未新增普通 P1。

### 阶段 54：真实进程有界 SSE 并发读取与事件连续性（2026-09-08）

- `a12-0343-sse-*` 真实 Go+PG：8 条并发 SSE 保持期间写入 12 条消息；8/8 均收到 ID `3–14`，每条 12 个、无重复且连续。
- DB 核对 `activity_events=14|mission_messages=13|agent_runs=13|generation_jobs=0`；无 Connection，Provider 调用 0，Run 为 `WAITING_INPUTS`。
- 首次结果汇总脚本有 PowerShell 数组解析错误，未计入结论；随后独立重新读取同一事件集并通过校验。资源/镜像/18105 清理为0。证据仅覆盖有界并发，不覆盖断线风暴/无限连接/生产吞吐。

### 阶段 56：上游跨服务幂等重试恢复合同补强

- 新增 `internal/rag/lessonforge_retry_test.go`：第一次 Java intake/Parse/Index 成功后，Go 本地 RAG binding 保存故障；第二次使用同一 `missionFileId + sourceSha256` 重试，消费同一 Java material ID 并恢复本地 binding。
- 普通 Go 全包、race 全包、RAG 定向和 Docker build `lessonforge-0351-go:current` 均通过，临时镜像已清理。
- 测试桩合同证据不冒充真实 Java 故障注入 live；真实 Java 成功后 Go 失败再重试的隔离演练仍 pending。未修改 Java/迁移/下游，严格停在 Locked 前。

### 阶段 57：上游跨服务真实幂等重试恢复实演（2026-09-08）

- 复核 0319 后发现 Worker 对本地 RAG binding 持久化失败会直接标记 `FAILED`，与冻结的“Java 成功、Go 失败可幂等重试恢复”不符；已做最小生产修复：新增 `ErrRetryableRAGBinding`，Worker 对该错误保持 `PENDING`，非永久 binding 错误包装为可重试，永久身份/权限错误仍 fail-closed。
- 全新 `a12-0352-*` 双 PostgreSQL + Java/Go 真实进程栈中，真实 PDF 经 Go Upload/Mission 首次发送后，Java intake/Parse/Index 成功；隔离 Go binding 第一次失败，下一轮同一 `missionFileId + sourceSha256` 恢复为 READY。Java binding 只有1条 BOUND，Go binding 只有1条且 material/SHA 一致，说明没有分叉材料。
- owner 自己 HTTP 读回 File=READY；第二教师访问 Mission/Files 均404。无 Model Connection，AgentRun=WAITING_INPUTS；没有 Planning/Generation/PPT Engine。
- 普通 Go 全包、race 全包和无缓存 Docker build 均 exit0，所有临时资源清理完成。阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0320_LessonForge上游跨服务真实幂等重试恢复实演_开发结果汇报_2026-09-08.md`。

### 阶段 58：上游真实 RAG 搜索读取与材料所有权防线（2026-09-08）

- 在 `a12-0353-*` 真实 Java/Go 双 PG 栈中，真实教师 PDF 经 Go Upload、Mission 首次发送、Java 新 LessonForge intake/Parse/Index 后，Go `live-upstream-check` 实际执行 Search/Read；返回同一真实 PDF 的 source/title、`hitCount=1`、score=`10`、`readBytes=320` 和真实内容前缀。
- 源码复核发现 Runtime `read_material` 原先未验证调用方 owner 与具体 MissionFile，已在 `internal/agent/runtime.go` 增加 `authorizeMaterialRead`：Mission owner、MissionFile id/file object id、READY、允许 role/provenance 均通过后才写 Activity、调用 RAG；跨教师稳定返回 `RAG_FILE_NOT_AUTHORIZED`，无 Activity 副作用。
- `internal/agent/worker_integration_test.go` 新增真实 PG 跨教师回归并断言 `AGENT_TOOL_CALLED=0`；`docker go test -count=1 ./internal/agent` exit0。无 DB 普通全包和 CGO race 全包 exit0，Docker no-cache build `lessonforge-0359-go:current` exit0；镜像及所有临时资源清理完成。
- 连接 DB 全量 exit1 的既有 Generation/Download/httpapi teardown 错误（`LOCKED_SPECIFICATION_IMMUTABLE` 与外键清理）单独记录，不修改 Generation/PPT Engine，不作为 RAG 失败结论。
- 本阶段无 Provider Key；没有调用 Planning/Generation/PPT Engine，`generation_jobs=0`、`artifacts=0`。阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0321_LessonForge上游真实RAG搜索读取与材料所有权防线_开发结果汇报_2026-09-08.md`。
### 阶段 59：AgentRun 数据库上下文重建与 Question→Locked 上游状态链（2026-09-08）

- 源码复核发现 Runtime 原先只加载 owner Mission 下的 Messages 与 Files，Question/Answer/Current Draft 没有在新 Run 启动时进入模型上下文；这不满足“从数据库重建上下文”的 Goal 合同。
- `internal/agent/runtime.go` 现按 owner 加载 Mission、Messages、Files、Questions（含 latest answer）与 Current Draft；没有 Draft 只容忍 `pgx.ErrNoRows`，其他错误失败；API Key/密文不进入上下文。
- `internal/agent/worker_integration_test.go` 新增两项真实 PG 回归：上下文请求检查和完整 `QUESTION → Answer → new AgentRun → PLAN_DRAFT → Approve → Locked`，直接 UPDATE 锁定规格失败，`generation_jobs=0`。
- `a12-0362-test-pg` 定向 2/2 exit0；无 DB 普通全包、CGO race 全包、Docker build `lessonforge-0363-go:current` 均 exit0，临时资源清理为0。
- 受控测试 transport/数据库身份模板仅为自动化状态机证据；真实 Provider/教师文件全链、RAG 后续实时消费、故障高压仍 pending；严格不进 Generation/PPT Engine。报告：`D:\服务外包正式文档\开发结果汇报存档\0322_LessonForge上游AgentRun数据库上下文重建与Question到Locked闭环_开发结果汇报_2026-09-08.md`。

### 阶段 60：Question/Answer 服务端合同与真实 HTTP 回归（2026-09-08）

- 发现并修复 `AnswerQuestion` 只检查非空、不按 server-owned Question 类型/选项校验的缺口；Store 在 owner-scoped `FOR UPDATE` 事务中 fail-closed，HTTP 返回安全 400。
- 真实 PostgreSQL 16.14 HTTP 测试通过：非法选择不落库且不创建新 Run；合法选择继续创建答案和新 AgentRun；跨教师隔离保持404。
- 普通 Go 全包、race 全包和定向 HTTP 回归均 exit0；`a12-0364-test-pg`/网络已清理；未进入 Java 旧 RequirementSummary、旧 Kimi/Mock、Provider 或 Generation/PPT Engine。
- 新报告：`D:\服务外包正式文档\开发结果汇报存档\0323_LessonForge上游QuestionAnswer服务端合同与HTTP回归_开发结果汇报_2026-09-08.md`。

### 阶段 61：真实存储 PPTX 到 Locked Specification 绑定回归（2026-09-08）

- 新增真实 PG 测试：隔离 StorageRoot 写入合法 PPTX，按 MissionFile/FileObject 身份落库，Approve 真实校验 SHA、大小、ZIP 结构并生成 Locked Specification。
- Locked binding 读回 missionFileId、fileObjectId、owner、文件 SHA/大小/存储键及 template file/profile version；executionReady 与 engineNativeProfilePresent 均为 false，generation_jobs=0。
- PostgreSQL 16.14 定向 exit0，资源已清理；隔离构造 PPTX 不冒充真实教师生产模板，未进入 Java 旧 RequirementSummary、Provider 或 Generation/PPT Engine。
- 新报告：`D:\服务外包正式文档\开发结果汇报存档\0324_LessonForge上游真实存储PPTX到Locked绑定回归_开发结果汇报_2026-09-08.md`。

### 阶段 62：跨服务 Parser/RAG 后续身份合同补强（2026-09-09）

- 发现 LessonForge Java intake 成功后，Go 的 parse/index/read 只携带 Java project/material 身份，不能证明 Go 侧 MissionFile 与源文件身份；这与冻结的“Service Token 只证明 Go 服务身份”不一致。
- Go 新增 `internal/rag.MaterialIdentity`，Parser Adapter、MissionResourceBinder 和 Java Client 在 parse/index/read 统一传递 mission、missionFile、owner、actor、source SHA、source size。
- Java 新增 `LessonForgeMaterialDtos.PipelineRequest`；parse/index 要求 JSON 身份体，read 要求同一组查询参数；PipelineService 在当前教师 actor、项目 owner、MissionFile/Java binding、SHA、size、材料文件大小全部匹配前拒绝。
- 旧 Java 公共 Material API 的 `CONFIRMED RequirementSummary` 门禁保留；新 LessonForge 路径没有接旧 RequirementSummary、Kimi 或 development Mock，也没有修改 Generation/PPT Engine。
- Go RAG 定向合同、普通全包、race 全包、Go build 均 exit0；Java Docker 编译/打包 exit0（361 main/84 test source，`-DskipTests`）。
- 修改后隔离 Java+PostgreSQL HTTP live 已通过：Flyway v19；teacher=1/project=7；intake material=9/binding=1；parse SUCCEEDED；index 1 chunk；read 310 bytes；同 SHA 重试复用 binding=1/material=9；SHA 篡改 parse=409，owner/actor 篡改 index=403。Java Maven 测试未运行，Docker 使用 `-DskipTests`。P0=0、P1=0、P2=1，报告见 `D:\服务外包正式文档\开发结果汇报存档\0325_LessonForge跨服务ParserRAG后续身份合同补强_开发结果汇报_2026-09-09.md`。

### 阶段 63：Go 业务路径 Parser/RAG 真实闭环（2026-09-09）

- 新鲜隔离 `a12-0370-*` 双 PostgreSQL + Go/Java/Parser 进程栈完成真实 Go 注册、Upload、New Mission；无 Model Connection 时 AgentRun=`WAITING_INPUTS`，没有隐式 Provider fallback。
- Go Worker 真实经过 Java LessonForge intake/Parse/Index：project=7、material=9、binding=BOUND；Go MissionFile=READY。Go `live-upstream-check` 真实 Search/Read 返回 1 条带 title/source 的命中和 320 bytes 内容。
- 同 `missionFileId + sourceSha256` intake 重试复用 material=9；第二教师访问 Go Mission/Files 为404；错误 owner/actor 访问 Java Read 为403；双库均无重复材料身份。
- 阶段 63 只证明 Parser/RAG 真实业务路径，不证明真实 Provider/Agent Tool Calling/Planning/Locked；P0=0、普通P1=0、P2=1（Java Maven 测试未运行），硬停止在 Locked 前。
- 详见 `D:\服务外包正式文档\开发结果汇报存档\0326_LessonForgeGo业务路径ParserRAG真实闭环_开发结果汇报_2026-09-09.md`。

### 阶段 64：Go AgentRun 租约恢复、SSE 与真实进程复核（2026-09-09）

- 无数据库 `go test -count=1 ./...` 与 `go test -race -count=1 ./...` 均 exit 0，15 个有测试的 internal 包通过。
- 全新 PostgreSQL 上 Agent、数据库状态机和 SSE 定向测试 exit 0，覆盖状态恢复、claim/heartbeat/lease expiry/reclaim/cancel、Draft→Locked 不可变性、SSE cursor 与 owner 隔离。
- 真实 Go HTTP 进程完成注册、登录、New Mission、消息读回、WAITING_INPUTS 和 SSE 事件读取；未选择 Connection 时未调用全局 Key 哨兵或 Provider。
- 全数据库测试的红灯来自 Goal 外 Generation/Download teardown 与 Locked 不可变约束/FK 清理冲突；未修改下游，Goal 仍硬停止在 Locked 前。
- 详见 `D:\服务外包正式文档\开发结果汇报存档\0327_LessonForge上游AgentRun租约恢复SSE真实进程复核_开发结果汇报_2026-09-09.md`。

### 阶段 65：Question→Answer 活动审计事件补强（2026-09-09）

- `AnswerQuestion` 在同一事务内写入 `question_answers`、回答消息、新 AgentRun 和脱敏 `QUESTION_ANSWERED` activity event；不记录回答正文或凭据。
- 全新 PostgreSQL HTTP 问题链测试 exit0；普通 Go 全包与 race 全包均 exit0，15 个有测试的 internal 包通过。
- 未修改 migration、Java、Generation/PPT Engine；真实 Provider/产品级 Planning 仍待凭据，Goal 硬停止在 Locked 前。

### 阶段 69：真实 DeepSeek Connection 与 AgentRun 上游重放（2026-09-09）

- 全新隔离 PostgreSQL + 当前 Go Docker 镜像中，真实教师通过 REST 创建 `OPENAI_COMPATIBLE` Connection，Verify 实际访问 `api.deepseek.com` / `deepseek-chat`，HTTP 200/`VERIFIED`。
- 同一 `modelConnectionId` 创建 Mission 后，真实 AgentRun 观察到 `QUEUED→COMPLETED`，真实 Provider 返回协议合法 `MESSAGE` 并持久化；`execution_audits` 直接读回 `USER_BYOK`、actor/owner、host、model、HTTP status，未输出 Key/Header/密文。
- PostgreSQL 直接核对 `planning_drafts=0`、`locked_specifications=0`、`generation_jobs=0`、`artifacts=0`；此前 PowerShell 空数组显示误报已纠正，不能把显示结果当数据库事实。
- 本阶段未触发 Tool（Mission 无材料），不扩展为 RAG/Question/Draft/Locked 全链证据；Generation Worker 保持关闭。临时容器、镜像和端口已清理。
- 阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0332_LessonForge真实DeepSeek连接与AgentRun上游重放_开发结果汇报_2026-09-09.md`。

### 阶段 68：上游临时上传物理清理与绑定保护回归（2026-09-09）

- 新增真实文件存储 + PostgreSQL 回归，验证过期未绑定 Upload 的清理顺序：`CleanupExpiredUploads` 返回 `StorageKey`，物理文件删除成功后 `FinalizeExpiredUpload` 删除 `uploads/file_objects` 元数据。
- 未绑定文件的物理路径与数据库行最终均不存在；已绑定到 Mission 的 Upload 不进入清理候选，绑定物理文件仍可读取。
- 全新 PostgreSQL 定向测试 exit 0；gofmt 成功；未调用 Provider，未启动 Java/RAG，未进入 Generation/PPT Engine。
- 阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0331_LessonForge上游临时上传物理清理与绑定保护回归_开发结果汇报_2026-09-09.md`。

### 阶段 67：Mission 创建公开 HTTP 活动审计与教师隔离回归（2026-09-09）

- 新增 `internal/platform/httpapi/mission_creation_activity_integration_test.go`，从真实 `POST /api/missions` 入口验证 Mission、首条消息、临时上传绑定和已选 Connection 的事务结果。
- 全新 PostgreSQL 实际返回 HTTP201/`WAITING_INPUTS`；上传状态为 `BOUND`；真实 SSE 事件按 `MISSION_CREATED → MODEL_CONNECTION_SELECTED → MISSION_FILE_BOUND → MESSAGE_CREATED` 到达，事件引用 ID 与数据库一致。
- 第二教师访问同一 Mission 的 SSE 返回 HTTP404；该测试同时证明公开 API 的 owner 隔离和活动流读取，不调用 Provider。
- 当前源码普通 Go 全包与 race 全包均 exit0；全新 PostgreSQL HTTP 定向测试 exit0；临时容器、网络和端口已清理。
- 合成 VERIFIED Connection 仅用于事务/权限测试，不是 Live Provider 证据；真实 Provider、产品级 Tool Calling、Question/Answer 后续、Draft/Approve/Locked 仍 `LIVE_VERIFICATION_PENDING/PARTIALLY VERIFIED`。
- 未修改 Java、migration、RAG Adapter、Provider、Generation 或 PPT Engine；Goal 继续硬停止在 Locked Specification 前。
- 阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0330_LessonForge上游Mission创建HTTP活动审计与教师隔离回归_开发结果汇报_2026-09-09.md`。

### 阶段 66：Mission/File/Connection/Parser 活动审计顺序与幂等补强（2026-09-09）

- 修复 `CreateMissionAtomic` 活动插入顺序：Mission 创建后写 `MISSION_CREATED`，创建请求带连接时写 `MODEL_CONNECTION_SELECTED`，真实上传绑定后写 `MISSION_FILE_BOUND`，首条教师消息后写 `MESSAGE_CREATED`。
- `SetMissionConnection` 在选择确实变化时，在同一事务写 `MODEL_CONNECTION_SELECTED` 或 `MODEL_CONNECTION_CLEARED`；owner、enabled、VERIFIED 和原有冻结 AgentRun 快照门禁保持不变。
- `SetConnectionEnabled(false)` 清除已选连接时，同一事务按受影响 Mission 写 `MODEL_CONNECTION_CLEARED`，并保留原有 AgentRun 取消行为。
- `SaveMissionFileParseResult` 在 owner-scoped MissionFile READY 更新后写 `FILE_PARSE_READY`；以 MissionFile 加解析结果 SHA-256 指纹作为幂等键，重复 Parser 重试不重复生成事件。
- 新增 `TestCreateMissionAtomicOrdersMissionFileBindingActivity`；扩展解析和连接选择数据库断言。
- 无数据库 Go 全包与 race 全包均 exit0；全新 PostgreSQL 隔离环境下 `go test -count=1 ./internal/platform/database` exit0；容器和端口已清理。
- 本阶段未调用真实 Provider，未启动 Java/RAG，未修改 migration/Generation/PPT Engine；真实产品级 Planning/Locked 仍按 pending 边界，硬停止不变。
- 阶段报告：`D:\服务外包正式文档\开发结果汇报存档\0329_LessonForge上游活动审计事件顺序与幂等补强_开发结果汇报_2026-09-09.md`。
- 详见 `D:\服务外包正式文档\开发结果汇报存档\0328_LessonForgeQuestionAnswer活动审计事件补强_开发结果汇报_2026-09-09.md`。

### 阶段 70：上游真实闭环 MIME 修复与 DeepSeek 链路重放（2026-09-09）

- 发现浏览器 multipart 对 PPTX 发送 application/octet-stream 会使 Java LessonForge intake 无法稳定识别模板类型；Go 仅在空值/通用 MIME 时按有限扩展名映射 PDF、PPTX、DOCX、XLSX、图片、文本和视频，保留未知类型的通用回退。
- 仅修改 internal/platform/storage/storage.go 与 storage_test.go；新增真实 multipart 解析测试和 DOCX/MD/PPTX/XLSX MIME 归一化测试。
- 全新隔离 a12-0410 双 PostgreSQL + Go/Java 服务中，教师 user=2 上传真实 PDF 与真实 USTC PPTX；Mission 3 创建 HTTP201，MissionFile 4/5 均 READY；Java binding owner/actor=2、rag project=3、material=3/4、BOUND。
- 真实 AgentRun 依次经过两次 QUESTION、两次 question_answers 与新 Run；一次真实 Provider 空响应按 AGENT_FAILED 持久化；正常发送重放后 Run 79948dc6-416b-4316-abb5-ff14a25832c7 COMPLETED。
- Agent activity 真实包含 search_materials、read_material、get_template_capability、get_current_plan；真实 DeepSeek audit 为 OPENAI_COMPATIBLE、api.deepseek.com、deepseek-chat、USER_BYOK、HTTP200。
- Go 重启后同一 Connection Verify HTTP200/VERIFIED；双教师越权读取 Mission/Connection 均 HTTP404；没有输出 Key、Authorization 或密文。
- PLAN_DRAFT v1 非空且含 markdown/structuredPlanJson；Approve HTTP201 生成 LockedSpecification v1，绑定真实模板 SHA/size；数据库直接 UPDATE 返回 LOCKED_SPECIFICATION_IMMUTABLE。
- 现场数据库直接核对 requirement_summaries=0、planning_agent_traces=0、model_call_audits=0；generation_jobs=0、artifacts=0；没有进入旧 RequirementSummary、旧 Kimi、development Mock 或 PPT Engine。
- go test -count=1 ./... 与 go test -race -count=1 ./... 均 exit0；Go Docker build exit0。Java Maven 测试本轮未运行。
- 本轮报告：D:\服务外包正式文档\开发结果汇报存档\0333_LessonForge上游真实闭环MIME修复与DeepSeek链路重放_开发结果汇报_2026-09-09.md。状态为开发完成候选，等待全新独立只读 Luna 复审；P0=0，本轮新上游业务 P1=0。
- Generation Worker、Composer、Executor、ExecuteV2、Artifact、PPTX、Office 和 PPT Engine 继续冻结，未进入。

### 阶段 71：真实 Embedding、持久化向量检索与关键词降级（2026-09-17）

- Go `model.Client` 新增 OpenAI-compatible `/embeddings` 批量调用，限制批大小、校验返回数量/索引/维度，并保留安全供应商错误码；全局 `EMBEDDING` 绑定由数据库解析，密钥只在调用期间解密，不使用 Mission 的 PLANNING 模型。
- Java 新增 V20 `lessonforge_chunk_embeddings` PostgreSQL/H2 迁移、`JdbcKnowledgeVectorStore` 和 Embedding 内部接口；生产 PostgreSQL 使用 `double precision[]`，按 project/material/model/dimension 隔离并在 SQL 中计算余弦相似度，写入前校验 chunk 身份、数量、维度和有限浮点数。
- `search_materials` 在 Embedding 查询失败时明确转为 `KEYWORD_FALLBACK`，响应携带安全降级原因；成功返回 `VECTOR`、真实 chunk/material 身份和 PostgreSQL 向量算法信息。AgentRun 记录 RAG 调用成功/失败活动。
- 修复 Go Parser 与 Java chunk/material/file 身份边界：解析结果拒绝数字占位正文；真实解析后批量生成 Embedding 并持久化，Embeddings 未配置或维度不匹配时失败关闭，不再把伪向量或解析占位数据标成 READY。
- Docker Go 全量测试通过；Java Spring Boot 上下文测试通过；Docker 重建 `backend-api` 与 Go `server` 成功。生产 PostgreSQL Flyway 从 v19 升至 v20。
- 真实 Mission 77 / MissionFile 20 / Java project 14 / material 15 重跑：PDF 实际解析为 601 chunks；Go 使用 EMBEDDING connection 29（`BAAI/bge-m3`，1024 维）完成 601 次批量调用审计，Java 表中 601 行向量均为 READY，向量首值为真实浮点数且非伪随机占位。
- 真实 Java HTTP 检索返回 `retrievalMode=VECTOR`、`prototype=false`、`PostgreSQL double precision[] 向量余弦相似度`；同一接口无 query embedding 时返回 `retrievalMode=KEYWORD_FALLBACK`，并携带 `EMBEDDING_HTTP_429` 示例降级原因。未删除 Mission 77、文件或数据卷。
- 当前边界：本轮完整 Java 全量 Maven 测试尚未重新跑完；已通过 Spring 上下文测试、Docker 编译/打包、Go 全量测试和真实 PostgreSQL/Java/Go 链路。真实主动 Agent 是否在每次对话中触发 `search_materials`，仍需用教师实际对话或独立 Provider 回归继续验证。

### 阶段 72：产品方向真实验收失败——PDF正文占位与材料研究合同（2026-09-17）

- 按产品入口创建隔离 Mission 78，使用真实 PDF、真实 Go 用户 58、PLANNING connection 28 和 EMBEDDING connection 29；没有输入预写大纲，没有批准草案，没有生成 PPT。
- Go MissionFile 21 最终 READY；Java binding 为 project=15/material=16；Embedding 真实 HTTP 200 共 19 次，Java 向量表有 601 行 1024 维 READY 向量。
- 两次真实 AgentRun（`5d48e324-5dd8-42ab-89e4-e0eddf903ad9`、`e5484267-df79-43f6-9222-88cb804c431a`）均 FAILED，错误为 `AGENT_FAILED`，运行日志为 `SUBAGENT_MATERIAL_RESEARCHER_INVALID`。DeepSeek HTTP 200，但材料研究子智能体 JSON 合同不合格。
- 两次运行都没有 `AGENT_TOOL_CALLED/search_materials`、`RAG_SEARCH_COMPLETED`、`RAG_EMBEDDING_QUERY`、`read_material` 或 Planning Draft，不能声称本次真正读取了第 3.2 节。
- 产品数据质量核验发现 Java `parse_results.material_id=16` 的 extracted_text 为 5 位数字 `69496`，唯一 section 为 `68892`；601 个 knowledge chunks 正文全部为 5 位数字（`69498`—`70098`）。原始 PDF 第 89—97 页经 pypdf 可读出真实第 3.2 节正文，故问题在 Java 解析/分块结果，不在源 PDF。
- 验收结论：FAIL。当前 `READY + 向量 READY` 不代表教材正文可用；必须先修复 Java 正文持久化及数字占位门禁，再修复材料研究子智能体 JSON 合同并重跑真实 `search_materials → read_material → Planning Draft` 闭环。
- 报告：`D:\服务外包正式文档\开发结果汇报存档\LessonForge_产品方向真实验收_第3章3.2_RAG基础_2026-09-17.md`。

### 阶段 73：Java TEXT/OID 历史恢复与材料研究 Agent 合同修复（2026-09-17）

- 修复 Java `ParseResult`、`KnowledgeChunk` 的 PostgreSQL `@Lob` 到 `TEXT` 映射；新增 V21/V22 PostgreSQL 恢复迁移，按真实 `pg_largeobject` OID 校验后用 `lo_get` 恢复 `parse_results`、sections、knowledge chunks，写审计表，不删除 Large Object。生产库 V22 已应用；material=16 的 601 chunks 长度为 610–1000、数字占位数为 0，V22 恢复审计 1275 行且均 readable。
- Go Parser 入口统一拒绝数字 OID 占位；材料研究子智能体改为严格 EVIDENCE/SKIPPED 合同，支持一次受控合同重试；新增真实材料预检，实际执行授权 `search_materials`、`read_material`，并把 Java 搜索结果的 `chunkNo` 返回给 Go 以按命中 chunk 读取。搜索失败时保留审计并使用同一授权文件的 bounded `chunk:1` 回退，不伪造材料证据。
- 真实 Mission 79/80 验证了此前合同和工具调用边界：79 曾完成 search 但未完成 read，80 拒绝无工具调用的模型证据；均未被误报为成功。最终 Mission 85 / AgentRun `a1850000-0000-4000-8000-000000000085` 在真实 DeepSeek `deepseek-chat` + SiliconFlow `BAAI/bge-m3` 环境完成：`AGENT_TOOL_CALLED/search_materials`、`RAG_SEARCH_COMPLETED`、`AGENT_TOOL_CALLED/read_material`、材料研究、需求澄清、方案编排、`PLAN_DRAFT_CREATED`，状态 `COMPLETED`；Draft 9 页、3150 字符，来源边界明确标注，未把第 2 章命中冒充第 3.2 节正文。
- Mission 85 执行审计：材料研究/需求澄清/方案编排均为 DeepSeek HTTP 200、`USER_BYOK`；RAG embedding query HTTP 200；Go 全量 Docker build/test 通过；Java backend Docker 编译通过；H2 22 迁移与 PostgreSQL migration contract 定向测试 5/5 通过。Java 全量 Maven 测试本轮未运行。
- 本轮没有批准 Draft、没有启动 Generation/PPT Engine，也没有删除既有 Mission、文件、向量或 Large Object。当前仍有产品语义边界：关键词降级命中先返回第 3.2 目录证据，正文细节未命中时 Draft 会显式标记待补充，而不是无依据扩写。
- 新报告：`D:\服务外包正式文档\开发结果汇报存档\LessonForge_材料研究Agent合同与真实Planning闭环修复_2026-09-17.md`。
