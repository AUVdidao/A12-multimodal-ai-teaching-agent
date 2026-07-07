# A12-multimodal-ai-teaching-agent

多模态 AI 互动式教学智能体是一个面向教师备课场景的系统原型，目标是帮助教师从教学需求出发，快速生成课件、教案、课堂互动内容和可导出的教学资料。

项目围绕“教师提出需求 -> AI 澄清与确认 -> 资料增强 -> 内容生成 -> 预览修改 -> 文件导出”的闭环设计，适合作为服务外包比赛中的 SaaS 教学智能体原型。

## 技术栈

- 后端：Java 17+、Spring Boot、Maven
- 前端：Vue 3 + Vite 或 React + Vite
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

当前仓库处于初始化阶段，前后端工程目录将在后续任务中创建。计划目录结构如下：

```text
backend/
frontend/
deploy/
docs/
scripts/
```

后续本地启动命令预留如下：

```bash
cd backend
mvn spring-boot:run
```

```bash
cd frontend
npm install
npm run dev
```

## Docker 部署预留说明

后续会在 `deploy/` 目录中补充：

- 后端 Dockerfile
- 前端 Dockerfile
- docker-compose.yml
- Nginx 配置
- 云端部署说明

目标是支持本地 Docker Compose 演示，并能迁移到云服务器或云容器平台部署。

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

当前阶段：仓库初始化。

已完成：

- 创建项目仓库目录
- 初始化 Git
- 添加 Java Spring Boot + Vue + Node + Docker 适用的 `.gitignore`
- 添加项目 README

下一阶段将按照 Linear 中的 `A12` 项目任务推进工程骨架、后端模型、前端页面、Dify 接入、导出能力和部署流程。
