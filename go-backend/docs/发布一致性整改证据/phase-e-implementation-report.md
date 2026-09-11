# Phase E 实现阶段报告：显式 Generation 请求闭环

日期：2026-09-11
范围：Go backend、前端 Go Mission Workspace、前向 migration、隔离测试
安全边界：未启动 Worker，未替换运行中的 Go server，未向当前业务库应用 migration 013，未创建线上 GenerationJob，未调用 PPT Engine。

## 结论

实现层状态：`VERIFIED`。产品冻结的“Approve 与 Generate 分离”闭环已经落到源码、API、事务、前端按钮、轮询和 Artifact 展示。

发布就绪状态：`NOT_READY / BLOCKED FOR LIVE ACCEPTANCE`。唯一阻塞项是本阶段明确禁止执行真实 Worker → PPT Engine → Artifact READY 闭环，因此无法宣称生产生成已验收；当前运行中的业务库仍停在 migration 012，Worker 仍为 `LESSONFORGE_ENABLE_GENERATION_WORKER=false`。

## 1. Backend

状态：`VERIFIED`

- `D:\pri_work\LessonForge-go-backend\internal\platform\httpapi\server.go:103` 新增 `POST /api/missions/{id}/generation-jobs`；`server.go:952-989` 仅解析请求、调用 Store，并返回 Job ID、Specification ID、Specification Version、Status 与 `created`。
- `D:\pri_work\LessonForge-go-backend\internal\platform\database\store.go:2277-2348` 新增 `Store.CreateGenerationJob`。事务内锁定 Mission 行，校验教师归属、精确 LockedSpecification ID/Version、全部 MissionFile 为 `READY`、模板绑定与当前真实文件身份，创建 `QUEUED` Job 和 `GENERATION_REQUESTED` activity；HTTP handler 没有 SQL。
- `store.go:2350-2394` 复用 `templatebinding.Build` 校验模板 PPTX 元数据/解析状态/存储身份，并拒绝空或跨 Mission/Owner 的伪绑定。
- `store.go:2320` 对同一 Mission 的其它活跃规格返回 `GENERATION_ACTIVE_JOB_CONFLICT`；同一规格的 `QUEUED/RUNNING/VERIFYING` 重复请求返回已有 Job，不重复插入；FAILED/CANCELLED/SUCCEEDED 终态可创建新的 QUEUED Job。
- `store.go:2464-2489` 增加活跃 Job 查询；`store.go:2634-2668` 的 Job/Jobs 查询返回精确 `specificationVersion`，前端不再依赖规格 ID 猜版本。
- `ApproveDraft` 仍位于 `store.go:2194-2256`，没有新增 GenerationJob 写入；Worker 入口仍是既有 `ClaimGeneration`/`RunOnce` 消费路径。
- `D:\pri_work\LessonForge-go-backend\internal\model\types.go:178-193` 为 REST GenerationJob 增加 `specificationVersion`。

## 2. Migration

状态：`VERIFIED (source and isolated database)`；`NOT_VERIFIED (business database application intentionally skipped)`

- `D:\pri_work\LessonForge-go-backend\migrations\013_generation_job_retries.sql:5` 前向删除旧的 `generation_jobs_specification_uq`，允许同一 LockedSpecification 的终态重试；不删除任何数据行。
- `migrations/013_generation_job_retries.sql:7-12` 增加 Mission 查询索引和 `generation_jobs_active_mission_uq` 部分唯一索引，限制 `QUEUED/RUNNING/VERIFYING` 同一 Mission 只能有一个活跃任务。
- 013 已在独立临时 PostgreSQL 数据库随集成测试迁移并验证；测试结束后已删除该临时数据库。
- 当前业务库只读核对结果为 `001_init.sql` 至 `012_conversation_summaries.sql`，没有 013；这是本阶段的安全边界，不是遗漏。

## 3. Tests

状态：`VERIFIED`

- Go Docker builder：`docker build --pull=false --target build -t lessonforge-remediation-builder:20260911-generation-entry .`；Dockerfile 内 `gofmt -w cmd internal && go test ./... && go build ...` 通过，所有 Go 包通过。
- Store 集成测试：`D:\pri_work\LessonForge-go-backend\internal\platform\database\generation_request_integration_test.go:109-190`，在隔离数据库通过，覆盖 Owner、Version、Template、Materials、同规格重复、跨规格活跃冲突、FAILED 终态重试。
- HTTP 集成测试：`D:\pri_work\LessonForge-go-backend\internal\platform\httpapi\generation_job_integration_test.go:23-194`，在隔离数据库与临时 HTTP server 通过，覆盖 201 创建、200 重复返回、409 版本冲突、跨 Owner 404、终态重试及查询版本。
- 前端闭环测试：`D:\pri_work\A12-ppt-stage34-integration\frontend\test\goMissionQuestionMount.test.ts:315-358`，验证真实 POST payload、QUEUED 任务轮询至 Artifact、活动任务按钮禁用，以及已有 SUCCEEDED Artifact 时仍展示“再次生成 PPT”。
- 前端全量：`npm test` 通过，`90` tests / `90` pass / `0` fail；`npm run build` 通过（`vue-tsc --noEmit` 与 Vite production build）。测试输出中已有的 WebSocket `Port 24678 is already in use` 与 Vite `server is being restarted or closed` 为并行测试诊断噪声，进程最终退出码为 0。

## 4. Frontend

状态：`VERIFIED`

- `D:\pri_work\A12-ppt-stage34-integration\frontend\src\api\go.ts:132-139,205-208,377-379` 增加 `specificationVersion` 类型、创建响应类型和真实 Go API POST 客户端。
- `D:\pri_work\A12-ppt-stage34-integration\frontend\src\views\GoMissionWorkspaceView.vue:94-103` 在 LockedSpecification 之后显示条件检查和真实“生成 PPT/再次生成 PPT”入口；不满足模板或材料条件时不发请求。
- `GoMissionWorkspaceView.vue:191-202` 以 `QUEUED/RUNNING/VERIFYING` 作为活跃状态；历史 Artifact 不再阻止再次生成，FAILED/CANCELLED/SUCCEEDED 均可按规则重新请求。
- `GoMissionWorkspaceView.vue:298-342` 调用真实 POST，并每 1.5 秒读取现有 Mission detail API，直到终态；`GoMissionWorkspaceView.vue:94-103,345-347` 保留并展示真实 Artifact 下载/预览链接。
- `GoMissionWorkspaceView.vue:216-229` 修正历史 Artifact 与活跃重生成并存时的状态/进度显示，避免把进行中的重生成误报成已完成。

## 5. Product document

状态：`VERIFIED`

- `D:\pri_work\LessonForge-go-backend\docs\发布一致性整改执行任务书.md:19` 已更新为“显式入口已实现，真实闭环仍未执行”。
- `执行任务书.md:42` 纳入 migration 013；`执行任务书.md:87-96` 记录冻结的显式 POST、精确规格版本、模板/材料校验、活跃任务约束、终态重试、历史 Artifact 保留和 Worker 只消费规则。
- `执行任务书.md:137` 完成标准已包含 011/012/013；真实 PPTX 闭环仍是 READY 必要条件。

## 6. 未执行项与异常边界

- 未执行真实 Worker claim、Compose Plan、Engine execute、共享卷 Artifact 写入、Artifact READY/下载验收。
- 未启用 `LESSONFORGE_ENABLE_GENERATION_WORKER`，未创建业务 GenerationJob，未替换或重启当前 Go server，未在业务数据库执行 013。
- 早期无关诊断 `git -C D:\pri_work\LessonForge-go-backend status` 返回“not a git repository”；Go backend 目录没有 Git 元数据，未据此改动文件。
- 首次隔离集成测试尝试因全新 `golang:1.24-alpine` 容器下载 Go modules 时网络连接被拒绝而未执行测试；随后复用已成功构建的 Docker builder module cache，两个新增隔离集成测试均通过。

下一步唯一阻塞：在得到明确的运行授权后，使用当前已构建镜像、已备份业务库和已恢复的外部服务，单独执行一次真实 Generation acceptance；本报告不把静态/隔离测试结果提升为生产 READY。
