# A12-multimodal-ai-teaching-agent

多模态 AI 互动式教学智能体是一个面向教师备课场景的系统原型，帮助教师从教学需求出发，完成需求澄清、资料增强、教学意图确认、内容生成、预览修改和文件导出。

## 当前架构

- 前端：Vue 3、Vite、TypeScript、Element Plus
- 业务后端：Java 17、Spring Boot、Maven、H2
- AI 编排：Spring Boot 内部 `AIWorkflowGateway`
- 模型服务：Spring Boot 通过 Moonshot OpenAI-compatible API 直接调用 Kimi
- 离线降级：可选 Mock Provider，仅用于测试和演示保底
- PPT 成果执行：独立 PPT Harness + PPT Skill Runner，负责文件生成和自动几何质量检查
- 文件服务：独立解析服务和生成服务
- 部署：Docker Compose + Nginx 反向代理

项目不再依赖 Dify。需求澄清、需求摘要、资料分析、知识片段筛选、教学意图、生成方案、结构化内容和修改意图均由 Spring Boot 直接编排 Kimi。

## AI 工作流边界

```text
Vue
  -> Spring Boot business APIs
       -> AIWorkflowGatewayRouter
            -> KimiAIWorkflowGateway
            -> MockAIWorkflowGateway (optional fallback)
       -> PPT Harness / parser / generator services
```

Spring Boot 负责鉴权、项目上下文、输入控制、模型调用、JSON 解析与校验、错误降级、持久化和审计。PPT Harness 只负责复杂 PPT 任务执行和质量门禁，不接管项目业务流程。

## 本地启动

```powershell
copy .env.example .env
# 在 .env 中设置 MOONSHOT_API_KEY

docker compose config
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
```

默认入口：`http://localhost:8081`

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

## 关键环境变量

- `AI_PROVIDER=KIMI`：真实 Kimi 工作流；测试环境可设为 `MOCK`
- `A12_AI_FALLBACK_TO_MOCK=true`：真实模型不可用时是否降级
- `MOONSHOT_API_KEY`：服务端 Kimi API Key
- `KIMI_API_BASE_URL`
- `KIMI_WORKFLOW_MODEL`
- `KIMI_WORKFLOW_TIMEOUT_SECONDS`
- `KIMI_WORKFLOW_MAX_COMPLETION_TOKENS`
- `KIMI_ASSISTANT_MODEL`
- `KIMI_PPT_MODEL`

真实密钥不得提交到 Git。

## 主要业务闭环

1. 登录与角色工作台
2. 创建教学项目
3. 输入需求并进行 AI 澄清
4. 确认结构化需求摘要
5. 上传和解析教学资料
6. 本地知识检索与受控片段筛选
7. 确认教学意图
8. 生成并确认内容方案
9. 生成 PPT、教案和互动内容
10. 预览、修改、版本化、审批、发布与学情分析
