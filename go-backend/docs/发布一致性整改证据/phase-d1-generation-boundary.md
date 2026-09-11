# 阶段 D.1：Generation 边界与生产入口证据报告

- 核对日期：2026-09-11
- 任务依据：`D:/pri_work/LessonForge-go-backend/docs/发布一致性整改执行任务书.md`
- 核对范围：`D:/pri_work/LessonForge-go-backend` 当前磁盘源码、当前运行配置与已恢复的 C.2 外部服务状态
- 执行边界：只读审查与文档写入；未修改源码、未修改 `ApproveDraft`、未插入 GenerationJob、未启用 Worker、未执行真实 Generation、未处理 Artifact。

## 1. 状态与唯一阻塞项

状态：`BLOCKED`。

唯一阻塞项：当前生产源码没有明确的 `GenerationJob` 创建/入队入口，也没有已确认的产品动作授权在 `LockedSpecification` 之后显式请求生成。因此任务书要求的真实 Generation 闭环不能安全开始。

C.2 的 Engine、Backend API、Parser、Generator 已在既有 Compose project `a12-bridge` 中恢复并由 Go server 内网访问成功；本报告不把外部依赖健康误认为 Generation 已完成。

## 2. GenerationJob 创建入口核对

### 2.1 非测试生产源码

排除 `*_test.go` 后，当前 `internal` 与 `cmd` 源码中：

- `INSERT INTO generation_jobs` 命中数为 0。
- `CreateGenerationJob`、`QueueGenerationJob`、`EnqueueGeneration`、`RequestGeneration` 命中数为 0。
- `internal/platform/database/store.go:2333-2342` 的 `generationJobForTx` 只查询既有任务。
- `internal/platform/database/store.go:2344-2360` 的 `ClaimGeneration` 只从既有 `QUEUED` 或已过期 `RUNNING` 任务中选取一条，并通过 `UPDATE` 抢占租约；它不是创建入口。
- `internal/generation/worker.go:56-69` 只调用 `ClaimGeneration`，没有创建任务的分支。

### 2.2 测试夹具与历史路径

可见的 `INSERT INTO generation_jobs` 只出现在测试夹具：

- `internal/generation/worker_integration_test.go:94-96` 插入 `QUEUED` 测试任务。
- `internal/platform/database/store_integration_test.go:118-120` 插入带租约的 `RUNNING` 测试任务。
- `internal/platform/httpapi/download_integration_test.go:128-130` 为下载集成测试插入 `QUEUED` 测试任务。

这些路径不被生产二进制调用，不能作为生产入口或真实闭环证据。

### 2.3 从 HTTP/API 到 Generation 的完整实际调用链

当前实际链路在 `GenerationJob` 创建处断开：

```text
POST /api/missions
  -> internal/platform/httpapi/server.go:490-516 createMission
  -> internal/platform/database/store.go:584-635 CreateMissionAtomic
  -> INSERT missions + mission_messages + agent_runs
  -> AgentRun 队列；没有 generation_jobs

POST /api/missions/{id}/messages
  -> server.go:744-773 sendMessage
  -> store.go:637-650 CreateMessageAndRun
  -> store.go:653-685 createMessageAndRunTx
  -> INSERT mission_messages + agent_runs
  -> AgentRun 队列；没有 generation_jobs

agent.StartWorker
  -> internal/agent/worker.go:14-35 StartWorker
  -> worker.go:80-88 RunOnce
  -> store.go:1585-1610 ClaimAgent
  -> agent/runtime.go:63 onward Runtime.Run
  -> agent/runtime.go:358-410 persistFinal
  -> SaveDraftForRun / SaveQuestionForRun / AddAssistantMessageForRun
  -> 产生 PLAN_DRAFT、QUESTION 或 MESSAGE；没有 generation_jobs

POST /api/planning/{draftId}/approve
  -> server.go:930-937 approveDraft
  -> store.go:2190-2256 ApproveDraft
  -> INSERT locked_specifications + PLAN_APPROVED activity
  -> 返回 lockedSpecification；没有 generation_jobs

Generation Worker（若显式开启）
  -> generation/worker.go:37-54 Start
  -> worker.go:59-69 RunOnce
  -> store.go:2344-2360 ClaimGeneration
  -> 只消费既有 generation_jobs；不会补建任务
```

HTTP 路由证据在 `internal/platform/httpapi/server.go:74-107`：Generation 相关只有
`GET /api/missions/{id}/generation-jobs`、`GET /api/generation-jobs/{id}` 和
`POST /api/generation-jobs/{id}/cancel`（102-104），没有创建或请求生成的 POST 路由。

## 3. ApproveDraft 准确语义

`internal/platform/database/store.go:2190-2193` 的注释明确写出：`ApproveDraft` 是上游边界，只创建不可变 Locked Specification，故意不在此排队 Generation。实现证据如下：

- `store.go:2194-2224` 读取并锁定 Draft，复用已有 Locked Specification 或继续编译。
- `store.go:2246-2250` 只执行 `INSERT INTO locked_specifications` 并写入 `PLAN_APPROVED` 活动。
- `store.go:2253-2256` 提交并返回 `LockedSpecification`。
- `server.go:930-937` 的 HTTP handler 只调用该方法并返回 `lockedSpecification`。

当前生产调用中不存在 ApproveDraft 自动创建 GenerationJob 的路径。代码把“审批”和“下游生成”分开是当前实现语义；是否已经得到最终产品层面的确认，源码和本次现场均没有证据，不能擅自把它改成自动生成。

## 4. Generation Worker 的启动、消费与 fencing 边界

### 4.1 启动开关

- `internal/platform/config/config.go:81-84` 从 `LESSONFORGE_ENABLE_GENERATION_WORKER` 读取开关，默认值为 `false`。
- `cmd/lessonforge-server/main.go:118-125` 只有该配置为 true 才构造 `pptengine.Client`、`generation.Worker` 并调用 `Start`。
- 当前 Go server 运行配置在阶段 A 已脱敏核对为 `LESSONFORGE_ENABLE_GENERATION_WORKER=false`。因此当前进程仍启动数据库、Parser、Agent 等上游路径，但不启动 Generation scheduler，不执行 Generation `ClaimGeneration`、Engine 调用或 Artifact 导入。

### 4.2 消费循环与租约

- `internal/generation/worker.go:37-54` 用 750ms ticker 周期调用同一个 `RunOnce`；上下文取消时退出。
- `worker.go:59-69` 先 `recoverStaged`，再 `ClaimGeneration`；没有任务时返回成功等待下一轮。
- `internal/platform/database/store.go:213-235` 默认租约为 30 秒，heartbeat 间隔为租约三分之一且不少于 250ms。
- `store.go:2349-2354` 为每次 claim 生成 worker owner/token，并以 `FOR UPDATE SKIP LOCKED` 选择 `QUEUED` 或租约已过期的 `RUNNING` 任务，再更新为 `RUNNING`、写入 `lease_owner`、`lease_token`、`lease_expires_at` 和 `heartbeat_at`。
- `worker.go:85-91` 为执行设置 5 分钟 context，并默认启动 `renewLease`；`worker.go:267-282` 的 heartbeat 失败会取消执行。
- `store.go:2406-2412` 续租只接受匹配 job id、`RUNNING` 状态和 lease token 的更新，零行更新返回 `GENERATION_EXECUTION_FENCE_LOST`。
- `store.go:2415-2424` 完成状态更新同样要求 `RUNNING`、匹配 token、租约未过期；失去 fencing 时不宣称成功。

### 4.3 幂等与取消边界

- `worker.go:78-84` 在 Engine 调用前检查取消；`worker.go:117-128` 在 Engine 返回后再次检查取消、状态和 Artifact receipt。
- `worker.go:139-145` 用 `job.ID + leaseToken` 生成独立物理 storage key，避免旧租约与接管租约写同一文件。
- `store.go:2558-2632` 的 `CommitGenerationArtifact` 先按 job 查询已有 STAGED/READY Artifact，随后在持有匹配 lease 的事务中写入 file object、STAGED Artifact，并把 Job 推进到 VERIFYING；提交不确定时尝试按既有记录 reconcile。
- `store.go:2635-2677` 的 `FinalizeGenerationArtifact` 是使 Artifact 可见的唯一 READY 转换，并把 Job 推进到 SUCCEEDED；不确定提交留给下一轮恢复路径。
- `worker.go:214-264` 对 STAGED/VERIFYING Artifact 做恢复、物理校验、Finalize 后再次校验；不匹配时通过 `InvalidateGenerationArtifact` 失败关闭，而不把错误文件当作 READY。

## 5. PPT Engine、共享目录与 Artifact 失败状态

### 5.1 Engine 调用入口

- `cmd/lessonforge-server/main.go:122-123` 用 `LESSONFORGE_PPT_ENGINE_BASE_URL`、Artifact root 和 shared storage root 构造 Client/Worker。
- `internal/generation/worker.go:92-106` 的 V2 路径先构造可信 Engine package，调用 `ComposePlan`，再用 Engine 返回的 plan 构造 execute payload，调用 `ExecuteV2`。
- `internal/pptengine/client.go:63-102` 的 `ComposePlan` POST 到 `/internal/v1/compose-plan`，限制响应大小、解析 JSON、拒绝非 2xx 或空 plan。
- `client.go:105-145` 的 `ExecuteV2` POST 到 `/internal/v2/execute`，限制响应大小、解析 JSON、拒绝非 2xx。

### 5.2 共享目录与 receipt 校验

- Worker 通过 `EngineSharedStorageRoot` 构造 V2 package，并用 `generationStorageKey` 将自己的输出写入 Go storage；C.2 现场确认 Engine 与 Go server 共享 `lessonforge-engine-shared`，Engine 映射 `/engine-input`、`/engine-output`，Go 映射 `/app/data/files`。
- `worker.go:130-150` 读取 Engine receipt 指定文件并在入库前执行 storage 验证。
- `client.go:148-215` 的 `ReadArtifact` 拒绝绝对路径、反斜杠、越界/符号链接路径、空文件、超过上限、size mismatch 和 SHA-256 mismatch；对应失败码包括 `PPT_ENGINE_ARTIFACT_KEY_INVALID`、`PPT_ENGINE_ARTIFACT_NOT_FOUND`、`PPT_ENGINE_ARTIFACT_SIZE_INVALID`、`PPT_ENGINE_ARTIFACT_SIZE_MISMATCH`、`PPT_ENGINE_ARTIFACT_CHECKSUM_MISMATCH`。
- Engine/compose/execute/bridge/storage 失败由 `worker.go:96-115`、`worker.go:127-150` 写入 FAILED feedback；典型码包括 `PPT_ENGINE_NETWORK_ERROR`、`PPT_ENGINE_HTTP_<status>`、`PPT_ENGINE_COMPOSE_PLAN_MISSING`、`PPT_ENGINE_ARTIFACT_MISSING`、`PPT_ENGINE_ARTIFACT_*` 和 `ARTIFACT_STORAGE_INTEGRITY_FAILED`。
- Artifact READY/Job SUCCEEDED 只有在物理文件 size/SHA 通过、数据库状态转换和 READY 后二次校验均完成时才成立。

## 6. D.2 最小安全下一步

### 可执行（不涉及业务写入）

1. 保持当前 `ApproveDraft` 和 `LESSONFORGE_ENABLE_GENERATION_WORKER=false` 不变。
2. 由发布负责人基于本报告确认拟采用的显式生成动作位置、鉴权主体、幂等键和失败/取消语义。
3. 在产品确认前继续保留 C.2 外部服务的运行证据；不要用测试夹具或手工 SQL 作为生产 GenerationJob 入口。
4. 产品确认后，先做最小实现与针对真实 HTTP→Store→Worker→Engine→Artifact→下载链路的验收设计，再单独执行阶段 E。

### 必须产品确认

- 是否允许在 `LockedSpecification` 之后由教师显式点击/调用“请求生成”，而不是审批即生成。
- 生成请求是否必须绑定当前 LockedSpecification 版本、Mission owner 和幂等键；重复请求返回既有 Job 还是拒绝。
- 生成失败、取消、重试、历史缺失 Artifact 的可见语义，以及是否允许启用 Worker。

## 7. 未核验项

- 未核验产品负责人对显式 Generation 请求 API/交互和幂等语义的确认。
- 未核验实现该入口后的真实 HTTP 生产调用；因此没有 Job ID、真实 PPTX、Artifact ID、文件 size/SHA-256 或 API 下载证据。
- 未核验 readiness endpoint 是否已实现依赖级语义；当前 `/healthz` 仍不能证明数据库 migration、队列、shared storage 或外部依赖。
- 未处理历史 7 条 READY Artifact 中的 6 条物理缺失文件；没有删除或改写这些记录。

## 8. D.1 结论

当前源码边界已验证：AgentRun 负责上游规划输出，ApproveDraft 负责锁定规格，Generation Worker 只消费显式存在的 GenerationJob；生产创建入口缺失。因此在产品确认和入口实现前，发布状态必须保持 `BLOCKED`，阶段 E 不得开始。
