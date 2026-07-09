# 本地 Docker 原型部署基线

本文档用于说明 A12 多模态 AI 教学智能体在当前 main 基线上的本地 Docker 原型部署方式。当前基线覆盖 TA-005 Mock AI Workflow、TA-006 项目创建与生成模式选择、TA-009 多轮对话记录。

## 覆盖范围

- 后端 Spring Boot 服务容器化，默认端口 8080。
- 前端 Vue 构建产物由 Nginx 托管，默认映射到本机 8081。
- Nginx 将 `/api` 请求反向代理到 Docker Compose 内部的 `backend:8080`。
- 后端沿用当前开发数据库方案：H2 文件数据库，数据保存在 Compose 命名卷 `backend-data` 中。
- Mock AI Workflow 默认启用，不需要真实 Dify Key。

当前基线不包含 A12-7 / TA-007 和 A12-8 / TA-008，因为这两个任务尚未合入 main。后续合入后需要重新执行本文档中的验证步骤。

## 数据库模式判断

当前 Docker 原型采用 H2 embedded 模式，不启动 MySQL 容器。依据是 `backend/src/main/resources/application.yml` 中的 `spring.datasource.url=jdbc:h2:file:./data/a12-teaching-agent`，且当前 main 没有要求 MySQL 才能启动。

`.env.example` 保留 MySQL 示例变量，仅为后续数据库容器化任务预留；本基线不会读取真实数据库密码。

## 启动前准备

1. 安装并启动 Docker Desktop。
2. 确认当前目录为项目根目录。
3. 如需自定义端口，可复制 `.env.example` 为 `.env` 后修改示例值。不要在 `.env` 中写入真实密钥。

`.env.example` 中的 `DIFY_API_KEY` 保持为空；当前原型部署使用 `AI_PROVIDER=mock`。

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

- 前端页面：http://localhost:8081
- 后端直连：http://localhost:8080
- 通过前端 Nginx 代理访问后端：http://localhost:8081/api

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

执行基础冒烟：

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

手动验证多轮对话接口时，先通过 `POST /api/projects` 创建项目，再调用：

```powershell
Invoke-RestMethod http://localhost:8081/api/projects/{projectId}/dialogues
Invoke-RestMethod http://localhost:8081/api/dialogues/{sessionId}
```

## 常见问题排查

- 端口占用：修改 `.env` 中的 `BACKEND_PORT` 或 `FRONTEND_PORT`，然后重新启动。
- Maven 构建失败：检查 Docker 构建日志中的依赖下载和 Java 编译错误。
- npm build 失败：检查前端依赖安装、TypeScript 类型错误和 Vite 构建日志。
- 前端访问后端失败：确认前端通过 `http://localhost:8081/api/...` 访问，Nginx 会代理到 `backend:8080`。
- `/api` 反向代理失败：执行 `docker compose ps` 确认 `backend` 和 `frontend` 均在运行。
- 数据库连接失败：当前使用 H2 文件数据库和 `backend-data` 卷；可执行 `docker compose down -v` 清理本地原型数据后重试。

## 后续复验

A12-7 v2 和 A12-8 v2 合入 main 后，需要重新执行：

```powershell
docker compose config
docker compose build
docker compose up -d
.\scripts\docker-smoke-test.ps1
docker compose down
```

届时还需要补充需求输入、缺失字段识别和主动追问接口的 Docker 冒烟验证。

后续 A12-7 / A12-8 合入后需要追加验证：

- `POST /api/projects/{projectId}/requirements`
- `GET /api/projects/{projectId}/requirements/latest`
- `POST /api/projects/{projectId}/clarification/check`
- `POST /api/projects/{projectId}/clarification/questions`
