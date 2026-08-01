# A12 五阶段前端任务：教学需求与资料中心

## 分配信息

- 负责人：innocence730
- 基线分支：`refactor/five-stage-teaching-agent-flow`
- 基线提交：`dcac6f9e35f25872412beb225a835a4bdc2c0542`
- 交付方式：从最新基线创建个人分支，提交后提供分支名、commit 和构建结果；不要直接合并 `main`。

## 任务范围

将前端教学需求和资料入口收口到五阶段工作流：

1. 教学需求页保持需求输入、澄清对话和需求确认的连续路径。
2. 资料中心页统一资料上传、用途绑定、解析状态和知识片段入口。
3. 旧入口可以保留兼容跳转，但用户主导航只显示“教学需求”和“资料中心”。
4. 只使用现有后端接口，不新增假数据或替代 API 响应。

## 允许修改

- `frontend/src/views/RequirementInputView.vue`
- `frontend/src/views/MaterialUploadView.vue`
- `frontend/src/api/clarification.ts`
- 这两个页面直接使用的前端类型和局部组件

## 禁止修改

- `frontend/src/router/index.ts`
- `frontend/src/components/ProjectWorkspaceNav.vue`
- `frontend/src/styles/global.css`
- `backend/`
- Dify、Kimi、PPT Harness 和数据库代码

## 验收标准

- 澄清问题携带 `questionId` 和 `targetField`，回答提交只发送 `questionId` 与 `answer`。
- 刷新页面不会把同一个待回答问题替换成另一个问题。
- 资料上传、用途绑定、解析状态展示和进入知识检索均可使用真实接口。
- 加载、空数据、接口失败状态有明确反馈。
- `npm.cmd ci` 与 `npm.cmd run build` 通过。

## 回填格式

完成后回填：修改文件、接口验证、构建命令、commit、遗留问题。暂不发送 GitHub 消息，由项目负责人统一分发和合并。
