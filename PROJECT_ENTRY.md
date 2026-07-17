# A12 项目文件入口

更新时间：2026-07-17

本文档是《A12 多模态 AI 互动式教学智能体》的统一工程入口。首次接手项目、切换任务或开始联调时，先阅读本文件，再进入具体模块。项目事实以当前 Git 分支、代码、自动化测试和实际运行结果为准；历史提示词与旧版 Word 文档只作为背景材料。

## 1. 开始前检查

在仓库根目录执行：

```powershell
git status --short --branch
git log --oneline -8
git remote -v
docker compose ps
```

基本原则：

- 不在脏工作区直接合并其他分支。
- 不覆盖来源不明的本地修改。
- 不把 `.env`、API Key、私钥或真实密码提交到 Git。
- 每个阶段使用独立分支、小提交和可重复验证命令。
- Mock 能力必须明确标识，不能描述成真实 Dify 能力。

## 2. 目录导航

| 路径 | 职责 | 首要入口 |
|---|---|---|
| `backend/` | Spring Boot 业务 API、数据持久化、权限、AI Gateway | `backend/pom.xml`、`backend/src/main/resources/application.yml` |
| `frontend/` | Vue 3 教师、领导、学生协同界面 | `frontend/package.json`、`frontend/src/router/index.ts` |
| `file-parser-service/` | PDF、DOCX、PPTX、TXT、MD 等资料文本解析 | 服务目录内的构建与启动文件 |
| `file-generator-service/` | PPTX、DOCX、互动 HTML、ZIP 生成 | 服务目录内的构建与启动文件 |
| `dify/` | WF-01 至 WF-07 DSL、契约样例和静态校验脚本 | `dify/README.md`、`dify/workflows/` |
| `docs/api/` | 当前前后端接口契约 | `docs/api/ui-v6-backend-api.md` |
| `docs/testing/` | 已完成阶段的测试与验收证据 | 对应阶段报告与检查清单 |
| `infra/` | 反向代理、监控等部署配置 | `docker-compose.yml` 中的引用 |
| `scripts/` | Docker 冒烟、集成验证和辅助脚本 | `scripts/docker-smoke-test.ps1` |
| `D:\服务外包正式文档` | 比赛正式文档、历史设计和最终发布材料 | 当前实现完成前暂缓更新 |

## 3. 当前业务链路

系统主链路按以下顺序推进：

```text
登录与角色识别
-> 教学项目创建
-> 教学需求输入与澄清
-> 需求摘要确认
-> 资料上传与解析
-> 本地知识检索与教学意图确认
-> 教学内容生成
-> 预览、修改与版本管理
-> 领导审批与发布
-> 学生阅读与提问
-> 教学分析与学情洞察
```

任何页面或接口只有在使用真实项目数据、刷新后能够恢复、失败状态可解释并通过验收后，才可以标记为完成。

## 4. 本地运行入口

### Docker 原型

```powershell
docker compose config
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
docker compose ps
```

默认入口：

- 前端与统一反向代理：`http://localhost:8081`
- 后端健康检查：`http://localhost:8081/api/health`
- 监控日志入口：`http://localhost:8082`

除非任务明确要求停止，验收后可以保持容器运行。

### 后端开发

```powershell
cd backend
mvn test
mvn spring-boot:run
```

本机未配置全局 Maven 时，使用项目环境中已安装的 Maven 可执行文件，不修改仓库来规避环境问题。

### 前端开发

```powershell
cd frontend
npm.cmd install
npm.cmd run build
npm.cmd run dev
```

## 5. Dify 智能体入口

架构边界：

```text
Vue 前端
-> Spring Boot 业务 API
-> AIWorkflowGatewayRouter
-> MockAIWorkflowGateway 或 DifyAIWorkflowGateway
-> 已发布的 Dify Workflow
-> 结构化 JSON 返回
-> 后端校验、持久化和版本管理
```

Dify 工作流资产：

| 编号 | 能力 | DSL |
|---|---|---|
| WF-01 | 需求澄清 | `dify/workflows/WF-01-requirement-clarification.yml` |
| WF-02 | 需求摘要 | `dify/workflows/WF-02-requirement-summary.yml` |
| WF-03 | 资料分析 | `dify/workflows/WF-03-material-analysis.yml` |
| WF-04 | 知识检索与教学意图 | `dify/workflows/WF-04-rag-teaching-intent.yml` |
| WF-05 | 生成方案 | `dify/workflows/WF-05-generation-plan.yml` |
| WF-06 | 结构化内容草稿 | `dify/workflows/WF-06-structured-content-draft.yml` |
| WF-07 | 修改意图 | `dify/workflows/WF-07-edit-intent.yml` |

协作方式：

- Codex 负责 DSL、输入输出契约、Gateway、测试和联调修复。
- 项目负责人负责在目标 Dify 实例导入 DSL、配置模型供应商、发布工作流并管理应用 API Key。
- API Key 只进入本地 `.env` 或部署平台 Secret，不通过聊天、截图或仓库传递。
- V1 使用平台预设密钥，普通教师、领导和学生不填写 API Key。

DSL 静态校验：

```powershell
powershell -ExecutionPolicy Bypass -File dify/scripts/validate-workflows.ps1
```

静态校验通过不等于目标 Dify 实例已经成功导入、发布或运行。

## 6. 验收入口

| 范围 | 最低验证 |
|---|---|
| 后端代码 | `mvn test` |
| 前端代码 | `npm.cmd run build` |
| DSL | `dify/scripts/validate-workflows.ps1` |
| Docker | `docker compose config`、构建、启动、冒烟 |
| 浏览器交互 | 桌面与移动视口真实页面流程、无溢出、无假按钮 |
| Dify | 目标实例导入、发布、真实调用、输出契约校验 |
| 合并 | `git diff --check`、工作区干净、分支已推送 |

## 7. 分支与提交规则

- `main` 只接收通过验收的阶段成果。
- 前端视觉、Dify Gateway、协同业务和部署改动分别使用独立分支。
- 不把业务实现、测试证据和大规模文档重写塞进一个提交。
- 合并前先同步最新 `main`，再执行本阶段完整验证。
- 旧分支和备份分支不作为当前实现事实来源。

## 8. 文档策略

实现阶段暂不更新 `D:\服务外包正式文档` 中的正式说明书。全部功能和部署链路稳定后：

1. 以 Markdown 更新持续维护的设计、接口、Dify、部署和测试文档。
2. 以实际代码、提交和测试结果校正文档内容。
3. 再生成 Word/PDF 里程碑发布版。
4. 将失效版本移动到 `99_归档与历史版本`，不直接删除历史材料。

