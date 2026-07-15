# A12-multimodal-ai-teaching-agent

多模态 AI 互动式教学智能体是一个面向教师备课场景的系统原型，目标是帮助教师从教学需求出发，快速生成课件、教案、课堂互动内容和可导出的教学资料。

项目围绕“教师提出需求 -> AI 澄清与确认 -> 资料增强 -> 内容生成 -> 预览修改 -> 文件导出”的闭环设计，适合作为服务外包比赛中的 SaaS 教学智能体原型。

## 技术栈

- 后端：Java 17+、Spring Boot、Maven
- 前端：Vue 3 + Vite + TypeScript + Element Plus
- AI 工作流：统一 `AIWorkflowGateway`，当前使用 Mock，预留 Dify Provider
- 数据库：H2 文件数据库（本地与 Docker 原型），生产数据库待配置
- 文件与产物：项目级资料存储、结构化 PPT/教案/互动成果；Office 与 ZIP 导出在 M4 实现
- 部署：Docker、docker-compose、Nginx、云服务器部署

## 核心功能

- 项目创建与项目列表管理
- 教学需求输入与智能澄清
- 需求摘要生成与教师确认
- 教学资料上传、解析与用途绑定
- 知识库增强与上下文检索
- 生成方案确认、课件内容生成与进度查看
- PPT、Word 和互动内容预览
- 自然语言修改反馈与版本管理（M4）
- PPTX、DOCX、HTML 互动内容文件导出（M4）
- Mock / Cloud 模式切换，避免演示时外部 AI 服务故障导致系统不可用

## 本地启动方式

当前仓库采用 monorepo 结构，后端工程位于 `backend/`。计划目录结构如下：

```text
backend/
frontend/
deploy/
docs/
scripts/
```

### 后端启动方式

进入后端目录：

```bash
cd backend
```

执行测试：

```bash
mvn test
```

启动后端服务：

```bash
mvn spring-boot:run
```

健康检查地址：

```text
http://localhost:8080/api/health
```

### 前端启动方式

进入前端目录：

```bash
cd frontend
```

安装依赖：

```bash
npm install
```

启动前端开发服务：

```bash
npm run dev
```

执行生产构建：

```bash
npm run build
```

默认访问地址通常为：

```text
http://localhost:5173
```

### 前后端联调说明

- 后端默认运行在 `http://localhost:8080`
- 前端通过 `VITE_API_BASE_URL` 调用后端接口
- 前端开发服务已配置 `/api` 代理，便于本地调试时访问后端健康检查
- 可复制 `frontend/.env.example` 创建本地 `.env`
- 首页可点击“检查后端状态”验证 `GET /api/health` 联通

### 后端数据库说明

开发阶段后端使用 H2 文件数据库，便于本地快速启动和验证 JPA 实体映射。

- H2 控制台地址：`http://localhost:8080/h2-console`
- JDBC URL：`jdbc:h2:file:./data/a12-teaching-agent`
- 用户名：`sa`
- 密码：空
- 数据库文件位置：`backend/data/`
- 数据库文件不会提交到 Git，`.gitignore` 已忽略 `backend/data/`、`data/`、`*.db`、`*.mv.db`、`*.trace.db`
- 后续部署阶段预留 PostgreSQL 或云数据库配置

## Docker 本地原型部署

当前已提供本地 Docker Compose 原型部署：

- 单一入口：`reverse-proxy` 默认仅绑定 `127.0.0.1:8081`，将 SPA 请求转发至内部 `frontend-web`，将 `/api/` 转发至内部 `backend-api`
- 前端：`frontend-web` 使用 Nginx 托管 Vue 生产构建，不直接暴露宿主机端口
- 后端：`backend-api` 运行 Spring Boot，默认使用 Mock AI Workflow 和 H2 文件数据库，不直接暴露宿主机端口
- 文件解析：`file-parser-service` 在 Compose 内网提供 PDF、DOCX、PPTX、TXT、MD 的真实文本提取
- 文件生成：`file-generator-service` 在 Compose 内网生成真实 PPTX、DOCX、互动 HTML 与 ZIP
- 日志查看：`monitor-log` 默认仅绑定 `127.0.0.1:8082`，访问地址为 `http://localhost:8082`
- 上传持久化：Docker named volume `backend-data` 挂载到 `/app/data`
- 前端地址：`http://localhost:8081`
- 反向代理健康检查：`http://localhost:8081/healthz`
- 后端健康检查（经反向代理）：`http://localhost:8081/api/health`

启动与停止：

```powershell
docker compose config
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
docker compose logs backend-api file-parser-service file-generator-service reverse-proxy --tail=100
docker compose down
```

演示验收后可保持容器运行，无需执行 `docker compose down`。本地上传默认写入持久卷中的 `/app/data/uploads`，上传单文件上限为 200 MB；当前正文解析为控制资源使用限制在 20 MB。`VITE_DEMO_MODE=true`、`A12_DEMO_SEED_ENABLED=true` 与固定演示凭据仅限本地演示；生产必须将两项设为 `false`，并覆盖所有演示密码或禁用演示账号。真实 Dify、向量数据库、对象存储、生产数据库和生产 HTTPS 尚未接入。

## API 文档

- [UI V6 后端接口契约](docs/api/ui-v6-backend-api.md)：覆盖教师工作台、项目列表、项目概览、需求澄清、需求摘要、资料工作台、知识检索和教学意图八个目标页面。
- [M3 内容生成接口契约](docs/api/m3-generation-api.md)：覆盖生成工作区、方案编辑确认、PPT/教案/互动成果生成与预览。
- [项目入口流程契约](docs/api/project-flow-contract.md)
- [AI 工作流契约](docs/api/ai-workflow-contract.md)

## 环境变量说明

不要提交真实 `.env` 文件。后续会提供 `.env.example` 作为配置模板。

计划使用的环境变量包括：

- `SPRING_PROFILES_ACTIVE`
- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `DIFY_API_BASE_URL`
- `DIFY_API_KEY`
- `DIFY_WORKFLOW_ID`
- `STORAGE_ENDPOINT`
- `STORAGE_ACCESS_KEY`
- `STORAGE_SECRET_KEY`
- `STORAGE_BUCKET`

所有密钥只应保存在本地环境变量、部署平台密钥管理或服务器环境中。

## 当前开发状态

当前阶段：M3 内容生成闭环已实现并进入验收。

已完成：

- M1 项目创建、需求澄清、摘要确认和多轮对话
- TA-011 资料上传、项目隔离存储和受控下载
- TA-012 资料用途绑定与回显
- TA-013 TXT/MD/PDF/DOCX/PPTX 正文提取、关键词、教学环节和失败重试；图片/视频诚实降级
- TA-014 本地知识片段索引、可解释关键词检索和来源追踪
- TA-015 教学意图生成、编辑、确认和刷新恢复
- TA-016 生成方案创建、编辑、确认和刷新恢复
- TA-017 至 TA-019 结构化 PPT、教案、互动问答生成与真实预览
- TA-020 生成工作区、状态门禁、首版成果和幂等生成
- 本地 Docker Compose、M1 至 M3 smoke、桌面与移动端浏览器验收证据

尚未实现：真实 OCR/视频转写、真实向量 RAG、真实 Dify、自然语言修改与多版本恢复、生产部署。当前 Compose 内网的 `file-parser-service` 已真实提取 TXT/MD/PDF/DOCX/PPTX 文本，`file-generator-service` 已真实生成 PPTX/DOCX/互动 HTML/ZIP。
