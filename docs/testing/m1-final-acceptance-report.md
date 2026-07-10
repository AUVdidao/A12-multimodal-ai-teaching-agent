# M1 最终验收报告

## 交付范围

M1 覆盖项目创建、生成模式、教学需求版本保存、缺失字段识别、Mock AI 主动追问、教师补充、对话持久化、结构化需求摘要编辑与确认。

## 完成状态

| 任务 | 状态 | 证据 |
|---|---|---|
| TA-005 AI Workflow 抽象与 Mock | 已完成 | status、clarification、generation-plan 回归 |
| TA-006 项目与生成模式 | 已完成 | 项目创建、列表、模式保存回归 |
| TA-007 教学需求输入 | 已完成 | save/latest、版本与校验测试 |
| TA-008 主动追问 | 已完成 | check/questions 与前端缺失字段控件 |
| TA-009 多轮对话 | 已完成 | AI/TEACHER 保存、项目与 session 查询 |
| TA-010 需求摘要 | 已完成 | generate/latest/update/confirm |

## 页面链路

`首页 -> 项目列表 -> 新建项目 -> 生成模式 -> 教学需求 -> 智能澄清 -> 需求摘要 -> M2 入口提示`

页面使用项目级路由保存 `projectId`，需求、对话和摘要均从后端恢复。未完成需求不能确认摘要，已确认状态持久化。

## API 清单

- `POST /api/projects/{projectId}/requirements`
- `GET /api/projects/{projectId}/requirements/latest`
- `POST /api/projects/{projectId}/clarification/check`
- `POST /api/projects/{projectId}/clarification/questions`
- `POST /api/projects/{projectId}/dialogues`
- `GET /api/projects/{projectId}/dialogues`
- `GET /api/dialogues/{sessionId}`
- `POST /api/projects/{projectId}/requirement-summaries/generate`
- `GET /api/projects/{projectId}/requirement-summaries/latest`
- `PUT /api/projects/{projectId}/requirement-summaries/{summaryId}`
- `POST /api/projects/{projectId}/requirement-summaries/{summaryId}/confirm`

## 验证结果

- 后端：66 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- 测试数据库：`jdbc:h2:mem:a12_test`；开发 H2 文件 SHA-256 前后保持一致。
- 前端：`vue-tsc --noEmit && vite build` 通过，仅保留既有 bundle-size 警告。
- Docker：config、build、up、readiness、完整 smoke、日志扫描、down 全部通过。
- Docker 动态资源：project `11`、requirement `6`、summary `3`、session `project-11-clarification`。
- 浏览器：项目 `10` 完成创建、模式选择、不完整需求、AI 追问、教师补充、刷新恢复、摘要编辑、确认和再次刷新恢复。
- 浏览器控制台：最终检查 0 errors、0 warnings。
- 日志：无未处理 500、Nginx 502、Bean 冲突、重复路由、H2 文件锁或真实 Dify 请求。

PR 地址、PR Head 和最终 `main` merge commit 以本轮任务最终报告为准。

## M1 已完成能力

- 教师需求可保存多版本并刷新回显。
- 缺失字段可确定性识别，Mock AI 生成稳定追问。
- AI 问题与教师补充形成可恢复的对话历史。
- 摘要来源可追溯到最新需求版本，支持编辑、确认和幂等确认。

## 尚未实现的 M2 能力

资料上传、文件解析、知识库、RAG、PPT/Word/互动内容生成、真实 Dify 和生产部署均未实现，也未在界面中标记为完成。

## 已知限制

- 当前 AI Provider 为确定性 Mock。
- Docker 原型使用文件型 H2，只适合本地演示。
- M1 不提供复杂权限、多人协同编辑或生产级审计。

## 验收结论

M1 功能代码已形成完整闭环，合并前自动化、Docker 和浏览器验收通过。合并后仍需在 `main` 上重复执行全量门禁。
