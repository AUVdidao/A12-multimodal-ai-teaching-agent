# LessonForge 版本矩阵

审计日期：2026-09-11

| 维度 | 当前现场值 | 证据 | 状态 |
|---|---|---|---|
| 磁盘源码 | D:/pri_work/LessonForge-go-backend；无 Git metadata；源码已有 migration 011/012 | 目录检查、source-manifest.sha256 | NOT_VERIFIED |
| Go server 镜像 | lessonforge-bridge-server:latest；digest sha256:e0a2ac59...；创建 2026-09-10 | docker image inspect | VERIFIED |
| 运行 server 容器 | lessonforge-bridge-server-1；使用上述 digest；2026-09-11 启动 | docker inspect | VERIFIED |
| 镜像内 migration | 只有 001–010；011/012 缺失 | 容器内文件清单和 hash | MISMATCH |
| PostgreSQL migration | schema_migrations 只有 001–010 | PostgreSQL 直接查询 | MISMATCH |
| model_connections schema | provider、capabilities、capability_verification 均缺失 | information_schema 直接查询 | MISMATCH |
| conversation_summaries | 表不存在 | to_regclass 直接查询 | MISMATCH |
| Compose 服务 | 只有 postgres、server；外部 Engine/RAG/Parser 通过外部网络命名 | docker-compose.yml | VERIFIED |
| 实际配置 | worker=false；Engine/RAG/Parser 目标与 Compose 默认值一致；共享存储为 /app/data/files | 容器环境变量、docker inspect | VERIFIED（配置值） |
| 外部服务运行 | Engine、RAG/Backend API、Parser 容器均 exited 255；lessonforge-a12-net 只有 server | docker ps、network inspect | BLOCKED |
| 数据库 Artifact | 7 条 READY；仅 1/7 物理文件存在且 size/hash 匹配 | PostgreSQL + server volume stat/sha256sum | MISMATCH |

## 版本链结论

磁盘源码 → 运行镜像：不一致。

运行镜像 → 数据库：一致到旧版本 010，但不是当前源码要求。

Compose 配置 → server 实际环境变量：配置值一致。

Compose 配置 → 外部服务实际状态：不一致，外部服务未运行。

整体版本链：MISMATCH，不能进入 READY。
