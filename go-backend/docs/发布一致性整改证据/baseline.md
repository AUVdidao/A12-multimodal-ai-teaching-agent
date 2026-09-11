# LessonForge 阶段 A 现场基线

- 审计日期：2026-09-11
- 执行边界：只读基线与安全快照；未修改源码、数据库、Compose、镜像、容器、网络、volume 或 Artifact。
- 任务书：D:/pri_work/LessonForge-go-backend/docs/发布一致性整改执行任务书.md
- 源码 manifest：同目录下 source-manifest.sha256

## 1. 源码边界

- D:/pri_work/LessonForge-go-backend 不是 Git checkout。
- D:/pri_work 也不是 Git checkout。
- 当前无法提供 commit、tag 或提交级 provenance；manifest 使用当前磁盘文件的 SHA-256 作为可回溯指纹。
- 关键当前文件指纹：
  - cmd/lessonforge-server/main.go: b48698d6f01f...
  - internal/platform/config/config.go: 03d3f2f674c5...
  - internal/platform/database/store.go: 1dbb6414c5a8...
  - internal/generation/worker.go: b91f29f34994...
  - internal/pptengine/client.go: 8ad47d123315...
  - internal/templatebinding/resolver.go: 3a8527956702...
  - migrations/011_model_connection_capabilities.sql: 76b5c5f78ff0...
  - migrations/012_conversation_summaries.sql: 892ab768e2ae...
  - docker-compose.yml: 695a165f4ab4...
  - Dockerfile: e5bd9093e460...

## 2. Docker 现场

| 组件 | 现场值 | 状态 |
|---|---|---|
| Go server | lessonforge-bridge-server-1；镜像 lessonforge-bridge-server:latest；digest sha256:e0a2ac59...；created 2026-09-10T02:20:42Z；started 2026-09-11T03:15:39Z | VERIFIED |
| PostgreSQL | lessonforge-bridge-postgres-1；postgres:16-alpine；digest sha256:57c72fd2...；healthy | VERIFIED |
| Engine | a12-bridge-ppt-engine-service-1；digest sha256:46ecd617...；exited，ExitCode=255 | BLOCKED |
| RAG/Backend API | a12-bridge-backend-api-1；exited，ExitCode=255 | BLOCKED |
| Parser | a12-bridge-file-parser-service-1；exited，ExitCode=255 | BLOCKED |

Go server 容器内 migration 只有 001–010，011/012 不存在。容器内二进制 SHA-256 为 829856c9...；源码目录旧二进制 SHA-256 为 8B41FC68...。

## 3. Compose 与实际运行配置

Compose 文件只声明 postgres、server 两个服务。实际 server 环境变量已脱敏核对：

- LESSONFORGE_ENABLE_GENERATION_WORKER=false
- LESSONFORGE_PPT_ENGINE_BASE_URL 指向 ppt-engine-service:8080
- LESSONFORGE_RAG_BASE_URL 指向 backend-api:8080
- LESSONFORGE_PARSER_URL 指向 file-parser-service:8080
- LESSONFORGE_TEMPLATE_CAPABILITY_URL 指向 backend-api 的 template-capability 路径
- LESSONFORGE_STORAGE_ROOT、LESSONFORGE_PPT_ENGINE_ARTIFACT_ROOT、LESSONFORGE_PPT_ENGINE_SHARED_STORAGE_ROOT 均为 /app/data/files
- LESSONFORGE_MIGRATIONS_DIR 为 /app/migrations

配置值与 Compose 默认值一致，但目标外部服务当前不在网络中。

## 4. 数据库现场

- 当前数据库容器 healthy。
- schema_migrations 已应用 001–010，最后为 010_mission_feedback.sql。
- 011_model_connection_capabilities.sql 未应用。
- 012_conversation_summaries.sql 未应用，conversation_summaries 表不存在。
- model_connections 缺少 provider、capabilities、capability_verification。
- model_connections 当前 10 条：OPENAI_COMPATIBLE enabled=true，其中 8 条数据库状态为 VERIFIED、2 条为 INVALID；last_verified_at 只覆盖到 2026-09-09，属于历史状态。
- locked_specifications=27。
- generation_jobs=27，其中 FAILED=20、SUCCEEDED=7。
- artifacts=7，READY=7。
- file_objects=11。
- GenerationJob 和 Artifact 记录时间集中在 2026-09-07，不能证明当前运行闭环。

## 5. 网络与共享 volume

- lessonforge-a12-net 当前只有 lessonforge-bridge-server-1。
- 从 Go server 容器解析 ppt-engine-service、backend-api、file-parser-service 均为 NO_DNS。
- lessonforge-engine-shared 存在，created 2026-09-07T14:51:13Z。
- Go server 挂载该 volume 到 /app/data/files。
- 已退出 Engine 曾挂载同一 volume 到 /engine-input 和 /engine-output。
- volume 拓扑可见，但没有当前 Engine 读写证据。

## 6. Artifact 物理核对

7 个数据库 READY Artifact 中：

- 1/7 物理文件存在，size 与数据库一致，SHA-256 与数据库一致。
- 6/7 对应 storage key 的物理文件不存在。
- 当前 volume 不能支持 7/7 READY 记录的下载验收。

## 7. 健康检查

实际 GET http://127.0.0.1:8090/healthz 返回 HTTP 200 和 {"status":"ok"}。源码该接口为固定常量响应，只表示 HTTP 进程存活，不检查数据库 migration、队列、共享 volume 或外部服务。

## 8. 阶段 A 结果

阶段 A 只读基线：已完成。

安全快照：已完成，详见 backup-evidence.md。

当前剩余问题：源码/镜像/数据库版本不一致、外部依赖停止、Generation Worker 关闭、生产 GenerationJob 创建入口未确认、历史 READY Artifact 存在物理缺失。

下一阶段前置条件：已满足数据库备份可验证条件；阶段 B 仍需在主任务批准/协调下执行镜像构建和前向 migration。
