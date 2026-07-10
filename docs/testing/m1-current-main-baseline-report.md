# M1 当前基线报告

## 基线

- 开发起点：`origin/main` at `254296226a253d25f3a7e80e2f732c19826d3e29`
- 工作分支：`m1-requirement-clarification-complete`
- 已有能力：TA-005 Mock AI Workflow、TA-006 项目与生成模式、TA-008 主动追问、TA-009 对话记录、TA-027 Docker 基线
- 本轮补齐：TA-007 教学需求版本保存、TA-008 前端接入、TA-009 流程联动、TA-010 摘要生成与确认

## 数据与接口边界

- 自动化测试使用 `jdbc:h2:mem:a12_test`。
- 本地开发与 Docker 继续使用文件型 H2。
- AI Provider 保持 `MOCK`，不调用真实 Dify。
- M1 不包含资料上传、RAG、PPT/Word 文件生成和生产部署。

## 验收口径

代码只有在后端测试、前端构建、Docker 完整 smoke 和真实浏览器链路全部通过后，才允许合并到 `main`。最终合并提交与验证证据记录在 `m1-final-acceptance-report.md` 和任务完成报告中。
