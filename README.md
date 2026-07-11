# A12-multimodal-ai-teaching-agent

多模态 AI 互动式教学智能体是一个面向教师备课场景的系统原型，目标是帮助教师从教学需求出发，快速生成课件、教案、课堂互动内容和可导出的教学资料。

项目围绕“教师提出需求 -> AI 澄清与确认 -> 资料增强 -> 内容生成 -> 预览修改 -> 文件导出”的闭环设计，适合作为服务外包比赛中的 SaaS 教学智能体原型。

## 技术栈

- 后端：Java 17+、Spring Boot、Maven
- 前端：Vue 3 + Vite + TypeScript + Element Plus
- AI 工作流：Dify 云端工作流，保留 Mock 模式兜底
- 数据库：云数据库或本地开发数据库
- 文件与产物：对象存储，支持 PPTX、DOCX 和 HTML 互动内容导出
- 部署：Docker、docker-compose、Nginx、云服务器部署

## 核心功能

- 项目创建与项目列表管理
- 教学需求输入与智能澄清
- 需求摘要生成与教师确认
- 教学资料上传、解析与用途绑定
- 知识库增强与上下文检索
- 生成方案确认、课件内容生成与进度查看
- PPT、Word 和互动内容预览
- 自然语言修改反馈与版本管理
- PPTX、DOCX、HTML 互动内容文件导出
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

- 前端：Nginx 托管 Vue 生产构建，并将 `/api/` 反向代理到后端
- 后端：Spring Boot，默认使用 Mock AI Workflow 和 H2 文件数据库
- 上传持久化：Docker named volume `backend-data` 挂载到 `/app/data`
- 前端地址：`http://localhost:8081`
- 后端健康检查：`http://localhost:8080/api/health`

启动与停止：

```powershell
docker compose config
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
docker compose logs backend --tail=100
docker compose down
```

本地上传默认写入 `./data/uploads`，单文件上限为 20 MB。真实 Dify、向量数据库、对象存储和生产 HTTPS 尚未接入。

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

当前阶段：M2 资料增强闭环。

已完成：

- M1 项目创建、需求澄清、摘要确认和多轮对话
- TA-011 资料上传、项目隔离存储和受控下载
- TA-012 资料用途绑定与回显
- TA-013 原型解析、关键词、教学环节和失败重试
- TA-014 本地知识片段索引、可解释关键词检索和来源追踪
- TA-015 教学意图生成、编辑、确认和刷新恢复
- 本地 Docker Compose、M1/M2 smoke、浏览器验收证据

尚未实现：真实 OCR、真实向量 RAG、真实 Dify、PPT/Word/互动内容生成、生产部署。
