# 阶段 D：GenerationJob 生产入口核对与阻塞报告

- 核对日期：2026-09-11
- 核对范围：当前磁盘源码 `D:/pri_work/LessonForge-go-backend`
- 执行边界：只读源码核对；未修改 `ApproveDraft`、未创建 GenerationJob、未启动 Generation Worker、未写入业务数据。

## 结论

当前状态：`BLOCKED`。

唯一阻塞项：生产路径没有明确的 `GenerationJob` 创建/入队入口，也没有已确认的产品动作授权在审批之后排队生成。没有这个入口，不能执行任务书要求的真实 Generation 闭环。

## 源码证据

- `internal/platform/database/store.go` 的 `ApproveDraft` 在注释和实现中都限定为上游边界：只创建不可变 `locked_specifications`，并记录 `PLAN_APPROVED`；该函数没有 `INSERT INTO generation_jobs`。
- `internal/platform/httpapi/server.go` 的 `POST /api/planning/{draftId}/approve` 只调用 `store.ApproveDraft`，返回 `lockedSpecification`。
- `internal/platform/database/store.go` 的生产实现只有 `generationJobForTx` 查询既有任务、`ClaimGeneration` 领取既有 `QUEUED`/过期 `RUNNING` 任务，以及后续状态/Artifact 处理；非测试源码没有 `INSERT INTO generation_jobs`。
- `internal/generation/worker.go` 消费 `ClaimGeneration` 的既有队列，不负责创建任务。
- `cmd/lessonforge-server/main.go` 仅在 `LESSONFORGE_ENABLE_GENERATION_WORKER=true` 时启动 Generation Worker；当前运行容器该开关为 `false`。
- 搜索 `internal` 与 `cmd` 并排除 `*_test.go` 后：`INSERT INTO generation_jobs` 命中数为 0；`CreateGenerationJob`、`QueueGenerationJob`、`EnqueueGeneration`、`RequestGeneration` 命中数为 0。测试文件中的直接插入不构成生产入口。

## 最小设计（待产品确认后实现）

保持 `ApproveDraft` 只负责锁定规格。新增独立、显式且有权限保护的“请求生成”动作，事务内校验 mission owner、当前 `LockedSpecification` 和幂等键，再创建一条 `QUEUED` `GenerationJob`；重复请求返回既有任务，不重复排队。该动作完成后，部署配置才可在已验证 Engine/共享存储/Artifact 下载链路后显式开启 Worker。

在获得产品确认并实现/验收该入口前，不执行任何真实 Generation，不使用测试 SQL 代替生产入口，也不把 `ApproveDraft` 的成功响应解释为已排队。

## 阶段 D 结果

外部依赖在阶段 C.2 已恢复并通过内网健康检查；但 D 的唯一阻塞项仍未满足，因此阶段 E 真实 PPTX 闭环不能开始，发布状态保持 `BLOCKED`。
