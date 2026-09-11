# A12-multimodal-ai-teaching-agent

面向教师备课场景的多模态 AI 教学智能体。当前版本以 LessonForge 教学任务工作台为主线，覆盖需求澄清、资料解析、知识检索、教学方案确认、内容生成和产物管理，并保留独立的 PPT Engine、文件解析与文件生成服务边界。

## 当前版本架构

```text
Vue 3 / Vite / TypeScript
        │
        ▼
Spring Boot 业务后端
        ├── AIWorkflowGatewayRouter
        │    ├── KimiAIWorkflowGateway
        │    └── MockAIWorkflowGateway（测试/演示降级）
        ├── LessonForge Mission / Planning / Specification
        ├── Model Connection / Credential / Audit
        ├── Template / Material / Knowledge
        └── 远程文件解析、文件生成、PPT Engine
```

主要技术栈：

- 前端：Vue 3、Vite、TypeScript、Element Plus
- 业务后端：Java 17、Spring Boot、Maven
- AI 工作流：Spring Boot 内部 `AIWorkflowGateway`，生产路径使用 OpenAI-compatible 的 Kimi 接口
- 文件服务：独立 `file-parser-service` 和 `file-generator-service`
- PPT：独立 `ppt-engine-service`，通过受控 HTTP 合同调用
- 数据库：本地原型可使用 H2；Docker 运行使用 PostgreSQL 迁移路径
- 部署：Docker Compose、Nginx 反向代理

本版本不再依赖 Dify 作为默认工作流执行入口。外部模型不可用时，只有显式开启的 Mock 配置才允许降级；系统不会把等待、失败或未配置能力伪装成成功。

## 业务规则

方案确认和 PPT 生成是两个独立动作：

```text
Planning Draft
    ↓
教师批准草案
    ↓
Locked Specification
    ↓
教师单独点击“生成 PPT”
    ↓
Generation Job
    ↓
PPT Engine
    ↓
Artifact
```

批准草案只负责生成不可变的锁定方案，不自动创建 Generation Job。重新生成可以复用同一份 Locked Specification；如果教学内容发生变化，则创建新的方案版本后再生成。

## 主要业务闭环

1. 登录与角色工作台
2. 创建 Mission 并输入教学需求
3. AI 澄清需求并生成结构化摘要
4. 上传、解析和绑定教学资料
5. 知识片段筛选与检索增强
6. 确认教学意图与内容方案
7. 创建 Locked Specification
8. 单独创建并跟踪 Generation Job
9. 通过文件服务和 PPT Engine 生成产物
10. 预览、下载、修改、版本化和提交教学产物

## 本地构建与测试

后端测试：

```powershell
cd backend
mvn test
```

前端构建：

```powershell
cd frontend
npm.cmd install
npm.cmd run build
```

单独运行本仓库的 A12 Compose 原型：

```powershell
docker compose config
docker compose up -d --build
```

默认入口通常为 `http://localhost:8081`，健康检查为 `http://localhost:8081/healthz`，后端健康检查为 `http://localhost:8081/api/health`。

当前本地的 LessonForge 统一部署由独立的 Compose 控制目录管理。该控制目录统一容器命名为 `lessonforge-*`，但仍保留 Go 数据库和 A12 数据库两套独立数据边界；不要把两套数据库直接合并。

## 关键环境变量

- `AI_PROVIDER=KIMI`：使用真实 Kimi 工作流
- `A12_AI_FALLBACK_TO_MOCK=false`：生产/验收默认不伪造模型成功
- `MOONSHOT_API_KEY`：服务端 Kimi API Key，不得提交真实值
- `KIMI_API_BASE_URL`
- `KIMI_WORKFLOW_MODEL`
- `KIMI_WORKFLOW_TIMEOUT_SECONDS`
- `A12_MATERIAL_PARSER_MODE=remote`
- `A12_ARTIFACT_GENERATOR_MODE=remote`
- `A12_PPT_ENGINE_MODE=remote`
- `A12_PPT_ENGINE_BASE_URL`
- `A12_DEMO_SEED_ENABLED`：仅限本地演示，生产应关闭

真实密钥、`.env`、本地上传目录、构建目录和截图输出均不应提交到 Git。请使用 `.env.example` 作为配置模板，并在部署环境中通过安全的环境变量或密钥管理系统注入真实值。

## 当前验收边界

代码构建通过或容器健康，并不等于完整生产闭环已经验收。正式启用 PPT 生成前，还必须验证：

```text
创建 Generation Job
    → Worker/服务执行
    → PPT Engine 生成文件
    → Go/业务层读取共享产物
    → SHA-256 校验
    → 下载
    → PowerPoint 打开
    → 保存、关闭、重新打开
```

Generation Worker 默认保持关闭，直到真实 PPTX 的 Open → Save → Reopen 验收通过。
