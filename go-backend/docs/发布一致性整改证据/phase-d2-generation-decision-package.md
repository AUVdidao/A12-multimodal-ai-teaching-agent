# 阶段 D.2：GenerationJob 创建入口决策包

- 日期：2026-09-11
- 前置证据：`phase-d1-generation-boundary.md`
- 执行边界：只基于当前源码设计和写本文档；本文的候选方案均未实现。未修改业务源码、数据库、运行配置或容器，未创建 GenerationJob，未启用 Worker。

## 1. 当前事实和设计约束

- 当前生产路由在 `internal/platform/httpapi/server.go:74-107`。Generation 相关只有查询 Job、取消 Job 和查询/下载 Artifact，没有创建 Job 的 POST 路由。
- `internal/platform/database/store.go:2190-2256` 的 `ApproveDraft` 只创建不可变 `locked_specifications` 并记录 `PLAN_APPROVED`；不能把它隐式改为排队生成。
- `internal/platform/database/store.go:2344-2360` 的 `ClaimGeneration` 只消费既有 `QUEUED`/过期 `RUNNING` Job。
- `migrations/001_init.sql:158-171` 定义了 `generation_jobs`；`migrations/002_hardening.sql:1-7` 为 `specification_id` 建立唯一索引并增加取消字段；`migrations/003_rework2_leases.sql:7-16` 增加租约字段和索引。当前 `GenerationJob` 模型（`internal/model/types.go:178-191`）没有幂等键字段。
- 因而当前数据库可以表达“一个 LockedSpecification 至多一条 Job”，但不能直接表达客户端幂等请求历史；重试语义也不能凭空从现有表结构推断。

以下候选方案是“待产品确认、待实现”的设计，不是当前已存在的生产入口。

## 2. 候选方案 A：显式 HTTP POST + Store 事务用例

### 2.1 入口与现有文件边界

建议新增显式教师动作，例如：

```text
POST /api/missions/{missionId}/generation-jobs
```

落点和调用关系：

```text
server.go 新增 requestGeneration handler
  -> generation request DTO 校验
  -> currentUser(r.Context()).ID 取得 owner
  -> Store.CreateGenerationJob(...)（新增方法，不能由 handler 拼 SQL）
  -> 返回 jobId/status=QUEUED
```

现有路由组已经在 `server.go:74-76` 使用认证和教师权限；现有取消 handler 在 `server.go:968-973`，可复用同样的 owner 边界。`main.go:77-80` 已明确 Store 是 GenerationJob 的业务状态层，因此 SQL 应封装在 Store 或其下层用例中。

### 2.2 事务与绑定规则

拟新增 `Store.CreateGenerationJob` 的单事务边界：

1. `BEGIN`，以 `owner + missionId` 锁定并校验 Mission 所属教师。
2. 读取请求指定的 `LockedSpecification`；必须同时匹配 `mission_id`、owner 可见的 Mission、明确的 `specification_id` 和 `version`。不得只接受一个没有版本绑定的自由文本。
3. 校验当前生成策略允许的状态；没有 LockedSpecification 时返回业务错误，不写 Job。
4. 按幂等键/绑定的 `specification_id` 查重；没有既有任务时插入一条 `status='QUEUED'` 的 `generation_jobs`，并写入 `GENERATION_REQUESTED` activity。
5. `COMMIT` 后返回既有或新建的 Job。

现有 `generation_jobs_specification_uq` 只能保证同一个 `specification_id` 一条记录；如果产品要求保留多次重试历史，必须先增加 attempt/idempotency 数据结构或明确复用同一 Job 的状态转移，不能依赖猜测。

### 2.3 幂等、重复、取消、重试

- 推荐请求携带客户端 `idempotencyKey`；若保存于 `generation_jobs`，需要新增 migration、模型字段和唯一约束（至少按 Mission/owner 作用域约束）。也可以新增独立 request/attempt 表，但那是更大的方案。
- 同一幂等键重复请求：在事务内返回同一 Job，不重复插入。
- 同一 `LockedSpecification` 但不同幂等键：现有唯一索引不允许再插入。候选语义是返回已有 Job，或返回“已有生成请求”；必须由产品确认。
- `QUEUED`/`RUNNING`/`VERIFYING`：重复请求不得再排队；返回既有任务状态。
- 已 `SUCCEEDED` 且 Artifact READY：返回既有成功结果，不重新生成。
- 已 `FAILED` 或 `CANCELLED`：当前没有安全的重试接口和历史策略。推荐先返回终态，另设经过确认的显式 Retry 语义；不得把重复 POST 自动解释为重试。
- 取消继续复用现有 `POST /api/generation-jobs/{id}/cancel`：`store.go:2481-2484` 对 QUEUED 直接变为 CANCELLED，对 RUNNING 设置取消请求；Worker 在 Engine 前后检查（`worker.go:78-84、117-125`）。产品仍需确认 VERIFYING 和重试状态的外部呈现。

### 2.4 优点和风险

- 优点：用户动作清晰、API 验收直接、变更面较小、容易证明 owner/版本/HTTP 权限边界。
- 风险：如果幂等、重试和版本字段直接堆在 `generation_jobs`，后续 CLI、定时任务或消息入口可能重复实现策略；需要严格禁止第二条绕过 Store 的插入路径。

## 3. 候选方案 B：独立 Generation Request use-case/service

### 3.1 入口与现有文件边界

新增独立应用用例，例如：

```text
internal/generation/request_service.go
  Request(ctx, RequestGenerationCommand) (model.GenerationJob, error)
```

HTTP 只是适配器：

```text
POST /api/missions/{missionId}/generation-requests
  -> httpapi handler（认证、参数解码、owner 注入）
  -> generation.RequestService.Request(...)
  -> Store/repository 的单事务校验与创建
  -> 返回 Job
```

该 Service 是唯一创建策略入口；未来若要接入 CLI、受控消息消费者或内部管理动作，均调用同一 use-case，不允许各自执行 `INSERT INTO generation_jobs`。当前仓库没有消息总线或该 Service，这些均属于待实现设计。

### 3.2 事务、绑定和状态策略

- Service 接收结构化命令：`ownerUserID`、`missionID`、`lockedSpecificationID`、`lockedSpecificationVersion`、`idempotencyKey`。
- Repository/Store 在一个数据库事务内完成 owner 校验、LockedSpecification 精确绑定、幂等查重、`QUEUED` 创建和 activity 记录；Service 不直接拿连接拼接业务 SQL。
- 生成执行仍由既有 `generation.Worker` 消费；Service 不调用 Engine，不写 Artifact，不改变 `ApproveDraft`。
- 取消仍走现有 owner 受限 Store 方法；重试可在未来增加独立命令和明确的 attempt 记录，避免把一次 Job 的历史覆盖掉。

### 3.3 幂等、重复、取消、重试

- 推荐把幂等请求和执行 attempt 分离：同一 request key 指向一个逻辑请求，同一 LockedSpecification 的每次实际重试可有独立 attempt；但这需要新的表/字段和迁移。
- 在没有该结构之前，Service 必须使用当前 `specification_id` 唯一约束，并对重复/终态返回稳定错误或既有 Job；不能声称已经支持多次重试。
- Service 可统一定义 `QUEUED -> RUNNING -> VERIFYING -> SUCCEEDED`、失败、取消的公开状态映射，但具体重试和失败可见语义仍须产品确认。

### 3.4 优点和风险

- 优点：创建策略只有一个权威用例，HTTP/未来入口不会复制幂等、版本和 owner 逻辑；更适合后续审计、重试和 request/attempt 分离。
- 风险：初期代码和测试面更大；如果没有至少一个真实 HTTP adapter，Service 本身不能算生产入口；不能以“抽象已设计”代替实际路由与数据库验收。

## 4. 推荐方案与必须确认事项

推荐方案 B 的“独立 use-case + 显式 HTTP adapter”，分两步落地：先由 Service 统一策略，再由一个教师显式 POST 路由作为第一个生产入口。推荐理由是当前系统已经把 AgentRun、ApproveDraft、Generation Worker 分成不同边界；把 Job 创建策略放入独立用例能避免未来第二个入口绕过 owner、版本和幂等校验。

若产品只接受最小改动，可先采用方案 A 的 `Store.CreateGenerationJob`，但必须保持 handler 薄、只有一个创建方法，并在后续迁移到 Service 前不增加第二条写入路径。

在任何业务源码实现前，必须由产品/发布负责人确认：

1. 生成是教师在审批后显式请求，还是审批后仍由别的明确动作请求；本包不建议审批即自动生成。
2. 请求绑定当前最新 LockedSpecification，还是必须由客户端提交精确版本；版本不匹配时返回何种状态。
3. 相同幂等键、相同规格不同幂等键、已有 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED 时的返回和重复策略。
4. 失败后是否允许 Retry；若允许，是复用 Job 还是新增 attempt/Job，历史记录如何保留。
5. CANCELLED、VERIFYING、Artifact 缺失/校验失败对教师的公开状态和再次请求语义。
6. 何时允许设置 `LESSONFORGE_ENABLE_GENERATION_WORKER=true`，以及是否要求单 Job 灰度窗口。

## 5. HTTP → DB → Worker → Engine → Artifact 下载最小验收矩阵

| 步骤 | 必须有的可观察证据 | 禁止越过的状态/边界 |
|---|---|---|
| 0. 发布基线 | 源码 manifest、不可变镜像 digest、schema_migrations=011/012、备份哈希一致 | 未有备份或源/镜像/DB 不一致时不得启 Worker |
| 1. 请求认证 | 教师身份、owner、Mission 归属；未授权/跨 owner 返回拒绝且 Job 行数不变 | 不得接受未认证请求；不得由客户端自带 owner 覆盖当前用户 |
| 2. 规格绑定 | 请求中的 `lockedSpecificationId/version` 与 Mission 精确匹配，查询结果可审计 | 没有 LockedSpecification、版本不匹配时不得创建 Job |
| 3. 创建 Job | 单事务提交证据；新增一条 `QUEUED` Job、`specification_id` 正确、activity 记录；重复幂等请求返回同一 Job | 不得直接用测试 SQL；不得在 ApproveDraft 内隐式创建；不得出现重复 Job |
| 4. Worker claim | Job 从 QUEUED 到 RUNNING；DB 中 lease owner/token/expires/heartbeat 存在；worker 日志只记录脱敏 ID/状态 | 未通过入口验收、没有明确开关批准时不得打开 Worker；不得无 token fencing 更新 |
| 5. Compose plan | Engine `/internal/v1/compose-plan` 收到可信 payload，HTTP 2xx、plan 非空、request/diagnostic 可追踪 | Engine 不可达、非 2xx、响应过大/非法/空 plan 时不得执行下一步或伪造成功 |
| 6. Execute | Engine `/internal/v2/execute` 返回受契约约束的 succeeded receipt，含 storage key、size、SHA-256 | Execute 失败、状态非成功或 receipt 为空时 Job 必须 FAILED，不得写 READY |
| 7. 共享卷桥接 | Go/Engine 映射到同一 `lessonforge-engine-shared`；物理 PPTX 存在；`ReadArtifact` size/SHA 通过 | 路径越界、符号链接、文件缺失、size/SHA 不匹配时不得入库为成功 |
| 8. Artifact 提交 | `CommitGenerationArtifact` 产生 STAGED/VERIFYING；DB file object、Artifact、Job 关联一致 | 未持有有效 lease 或提交结果不确定时不得宣称成功/删除未知归属文件 |
| 9. 最终化 | 二次物理校验后 Artifact=READY、Job=SUCCEEDED、activity=ARTIFACT_READY | 未完成验证不得变 READY；校验失败走 FAILED/补偿，不得删除历史无关 Artifact |
| 10. API 查询/下载 | 查询返回正确 Job/Artifact；下载 HTTP 200、Content-Length 与数据库 size 一致，响应体 SHA-256 与数据库一致；跨 owner 拒绝 | `/healthz` 200 不能代替下载证据；缺失/篡改文件不得返回 200 |
| 11. Readiness | readiness 能分别识别 DB 连接/migration、Worker/队列、shared storage、Engine、RAG、Parser；故障时非 READY | 不能用固定 liveness `/healthz` 或单次测试绿灯宣称发布 READY |

## 6. 后续实施分阶段清单

### Phase 1：实现入口和测试（第一步）

这是允许开始的第一步：在产品确认后，实现推荐的 Service + 显式 HTTP adapter（或经批准的最小方案 A），必要时先写幂等/attempt migration；增加单元测试、Store 集成测试和 HTTP owner/重复请求测试。测试只验证入口创建 QUEUED Job、版本绑定、幂等、未授权拒绝和取消边界；不要打开生产 Worker，不要调用真实 Engine，不要改 `ApproveDraft`。

### Phase 2：构建和非生产链路核对

从同一磁盘源码构建不可变镜像，核对 migration、路由和配置；使用隔离测试数据验证 HTTP→DB→Claim 的最小链路。保持生产 `LESSONFORGE_ENABLE_GENERATION_WORKER=false`，不使用历史业务行做试验。

### Phase 3：受控 Generation 预验收

在产品批准入口和 Worker 开关后，选择明确的测试 Mission/LockedSpecification，记录 Job ID；先验证 Engine compose-plan/execute、共享卷、size/SHA 和失败关闭路径，再允许一次受控真实 PPTX。任何一步失败立即停止，不删除历史 Artifact。

### Phase 4：Artifact/API 完整验收

完成 STAGED/VERIFYING/READY/SUCCEEDED 状态证据、API 查询、下载 HTTP 状态/Content-Length/SHA-256 和跨 owner 拒绝；保留可回退备份和脱敏时间线。

### Phase 5：Readiness 与发布结论

补齐或核验 readiness 依赖语义，单独列出历史 6 个缺失物理 Artifact 的异常清单和后续修复任务；只有所有任务书完成标准均有现场证据时才能从 BLOCKED 改为 READY。

## 7. D.2 结论

状态：`BLOCKED`。

阻塞项仍为唯一的产品/发布决策：是否采用“审批后显式请求生成”及其版本、幂等、取消、重试语义。方案已基于真实现有代码给出，但没有任何候选入口已经实现；因此不能进入真实 Generation，也不能把测试夹具或手工数据库插入当作生产闭环。
