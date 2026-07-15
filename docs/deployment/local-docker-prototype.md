# 本地 Docker 原型部署基线

本文档用于说明 A12 多模态 AI 教学智能体的本地 Docker 原型部署方式。当前可运行基线覆盖 M1 需求澄清、M2 资料增强、M3 内容生成闭环，以及 C6 的单入口反向代理、容器日志监控、文件解析和文件生成服务。

## 覆盖范围

- `backend-api` 运行 Spring Boot API，只在 Compose 网络中监听 `8080`，默认不公开宿主机端口。
- `frontend-web` 仅由 Nginx 托管 Vue 构建产物，只在 Compose 网络中监听 `80`，不代理 `/api`。
- `reverse-proxy` 是唯一浏览器入口，默认仅绑定 `127.0.0.1:8081`：`/api` 转发至 `backend-api:8080`，其他请求转发至 `frontend-web:80`；它提供 SPA 回退、安全响应头和 `/healthz` 健康检查。
- `monitor-log` 使用 Dozzle 读取 Docker socket（只读），默认仅绑定 `127.0.0.1:8082`，展示实际容器日志。
- `file-parser-service` 是内部真实解析服务，只在 Compose 网络中监听 `8080`。它仅接收受限 multipart 文件字节流和最小元数据，提供 `GET /internal/health` 与 `POST /internal/file-parser/parse`；不接收宿主机或容器文件路径。
- 后端沿用当前开发数据库方案：H2 文件数据库，数据保存在 Compose 命名卷 `backend-data` 中。
- Mock AI Workflow 默认启用，不需要真实 Dify Key。
- 资料正文解析由 `file-parser-service` 提供 TXT、MD、PDF、DOCX、PPTX 的实际提取；图片和视频仍按未启用 OCR、未启用转写诚实降级。Docker 中的 `backend-api` 使用远程解析适配器，测试与明确的 `local` 配置可继续使用本地确定性解析器。
- 内容生成持久化规范化 PPT、教案和互动问答 JSON，并由 Vue 页面真实预览。`file-generator-service` 在 Compose 内网实际生成 `.pptx`、`.docx`、互动 HTML 和 ZIP；公开 PPTX/DOCX 导出接口保持现有契约。

## 数据库模式判断

当前 Docker 原型采用 H2 embedded 模式，不启动 MySQL 容器。依据是 `backend/src/main/resources/application.yml` 中的 `spring.datasource.url=jdbc:h2:file:./data/a12-teaching-agent`，且当前 main 没有要求 MySQL 才能启动。

`.env.example` 保留 MySQL 示例变量，仅为后续数据库容器化任务预留；本基线不会读取真实数据库密码。

## 启动前准备

1. 安装并启动 Docker Desktop。
2. 确认当前目录为项目根目录。
3. 如需自定义端口，可复制 `.env.example` 为 `.env` 后修改示例值。默认 `HOST_BIND_ADDRESS=127.0.0.1`，避免将固定演示账号和 Dozzle 暴露到局域网；不要在 `.env` 中写入真实密钥。

`.env.example` 中的 `DIFY_API_KEY` 保持为空；当前原型部署使用 `AI_PROVIDER=mock`。`VITE_DEMO_MODE=true`、`A12_DEMO_SEED_ENABLED=true` 及示例固定凭据仅适用于本地演示；生产环境必须将两项设为 `false`，并覆盖全部演示密码或禁用演示账号。

解析服务默认配置为 `A12_MATERIAL_PARSER_MODE=remote`、`A12_MATERIAL_PARSER_BASE_URL=http://file-parser-service:8080` 和 `A12_MATERIAL_PARSER_TIMEOUT_MS=10000`。远程解析超时、不可达或返回错误码时，后端资料解析会进入既有 `FAILED` 状态并可重试，不会伪造成功结果。

## 启动与停止

启动：

```powershell
docker compose up --build
```

后台启动：

```powershell
docker compose up -d --build
```

停止：

```powershell
docker compose down
```

Windows PowerShell 也可以使用脚本：

```powershell
.\scripts\docker-up.ps1
.\scripts\docker-down.ps1
```

## 访问地址

- 浏览器和 API 单入口：http://localhost:8081（默认仅监听 `127.0.0.1`）
- 反向代理健康检查：http://localhost:8081/healthz
- 容器日志监控：http://localhost:8082（默认仅监听 `127.0.0.1`）
- `backend-api:8080` 和 `frontend-web:80` 仅可在 Compose 网络内访问，不提供默认宿主机业务端口。

如需临时诊断后端，可使用以下命令创建一次性本机回环端口映射；正常验收、冒烟和浏览器请求必须仍从 `reverse-proxy` 的 `/api` 进入：

```powershell
docker compose run --rm -p 127.0.0.1:8080:8080 backend-api
```

## 验证命令

检查 Compose 配置：

```powershell
docker compose config
```

构建镜像：

```powershell
docker compose build
```

启动容器：

```powershell
docker compose up -d
docker compose ps
```

执行 M1 至 M3 完整冒烟：

```powershell
.\scripts\docker-smoke-test.ps1
```

手动验证 Mock AI Workflow：

```powershell
Invoke-RestMethod http://localhost:8081/api/ai-workflow/status
```

手动验证项目与生成模式：

```powershell
Invoke-RestMethod http://localhost:8081/api/projects
Invoke-RestMethod http://localhost:8081/api/model-modes
```

冒烟脚本会动态创建独立项目并验证：项目创建、需求澄清、摘要确认、真实文本资料解析、知识检索、教学意图确认、生成方案编辑确认、三类成果生成和幂等恢复。手动验证多轮对话接口时，先通过 `POST /api/projects` 创建项目，再调用：

```powershell
Invoke-RestMethod http://localhost:8081/api/projects/{projectId}/dialogues
Invoke-RestMethod http://localhost:8081/api/dialogues/{sessionId}
```

M3 关键接口：

```text
GET  /api/projects/{projectId}/generation/workspace
POST /api/projects/{projectId}/generation-plans
PUT  /api/projects/{projectId}/generation-plans/{planId}
POST /api/projects/{projectId}/generation-plans/{planId}/confirm
POST /api/projects/{projectId}/artifacts/generate
GET  /api/projects/{projectId}/artifacts
GET  /api/projects/{projectId}/artifacts/{artifactId}
```

## 常见问题排查

- 端口占用：修改 `.env` 中的 `FRONTEND_PORT` 或 `MONITOR_PORT`，然后重新启动。
- Maven 构建失败：检查 Docker 构建日志中的依赖下载和 Java 编译错误。
- npm build 失败：检查前端依赖安装、TypeScript 类型错误和 Vite 构建日志。
- 前端访问后端失败：确认浏览器通过 `http://localhost:8081/api/...` 访问；只有 `reverse-proxy` 会将请求转发到 `backend-api:8080`。
- `/api` 反向代理失败：执行 `docker compose ps` 确认 `reverse-proxy`、`backend-api`、`frontend-web`、`file-parser-service` 和 `file-generator-service` 均在运行。
- 资料解析失败：先检查 `file-parser-service` 的 `/internal/health` 和容器日志。它没有宿主机端口；通过 `docker compose exec file-parser-service wget -q -O - http://127.0.0.1:8080/internal/health` 诊断。
- 日志监控打不开：确认 `monitor-log` 运行且 Docker Desktop 提供 `/var/run/docker.sock`；该挂载在 Compose 中为只读。
- 数据库连接失败：当前使用 H2 文件数据库和 `backend-data` 卷；可执行 `docker compose down -v` 清理本地原型数据后重试。

## 阶段复验

每次合入资料解析、内容生成、版本或导出变更后，都需要重新执行：

```powershell
docker compose config
docker compose build
docker compose up -d
.\scripts\docker-smoke-test.ps1
```

项目演示环境允许在复验后保持容器运行。需要销毁本地数据时才使用 `docker compose down -v`；该命令会删除 H2 和上传资料卷，执行前必须确认不再需要演示数据。

## C6 后续提取边界

`file-parser-service` 和 `file-generator-service` 已在本阶段以真实内部 API、健康检查、失败码和后端适配器加入 Compose。对象存储、向量检索和 Redis 尚未完成真实抽取，不创建空容器；后续仅在拥有独立运行时职责、健康检查、故障行为和集成验证后再加入 Compose。

## C6 phase 3: file generator service

`file-generator-service` 是内部专用 Spring 服务，没有宿主机端口。它提供 `GET /internal/health` 和以下 JSON 生成接口：

```text
POST /internal/file-generator/pptx
POST /internal/file-generator/docx
POST /internal/file-generator/interactive-html
POST /internal/file-generator/package
```

服务使用 Apache POI 写出真实 OOXML PPTX/DOCX，生成可独立读取的互动 HTML，并可返回所请求生成文件的 ZIP 包。它只接收结构化项目/成果字段及持久化 `contentJson`，不接收宿主机路径或读取项目文件；内容 JSON 上限为 1 MiB，ZIP 上限为 8 个条目。

Compose 中 `A12_ARTIFACT_GENERATOR_MODE=remote` 使 `backend-api` 调用 `http://file-generator-service:8080`，且 `backend-api` 会等待其健康检查。公开导出接口和前端契约保持不变。`A12_ARTIFACT_GENERATOR_MODE=local` 为本地/测试明确保留现有进程内 Apache POI 渲染器。远程校验失败仍返回公开导出的校验错误；远程不可用或超时时导出失败，不伪造文件字节。

仅用于内部诊断：

```powershell
docker compose exec file-generator-service wget -q -O - http://127.0.0.1:8080/internal/health
```
