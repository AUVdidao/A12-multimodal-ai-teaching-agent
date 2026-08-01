# A12 五阶段前端任务：大纲、教案与 PPT 成果

## 分配信息

- 负责人：ycxawait
- 基线分支：`refactor/five-stage-teaching-agent-flow`
- 基线提交：`dcac6f9e35f25872412beb225a835a4bdc2c0542`
- 交付方式：从最新基线创建个人分支，提交后提供分支名、commit 和构建结果；不要直接合并 `main`。

## 任务范围

将内容生成相关页面收口到三个连续阶段：

1. 课程大纲：展示教学意图确认后的大纲生成入口和状态。
2. 教案设计：展示教案生成结果、状态和下一步。
3. PPT 成果：统一预览与导出入口，展示生成状态、版本和下载操作。
4. 复用现有页面和接口，不重新实现 PPT 生成服务，不新增假数据。

## 允许修改

- `frontend/src/views/IntentConfirmView.vue`
- `frontend/src/views/GenerationPlanView.vue`
- `frontend/src/views/ArtifactPreviewView.vue`
- `frontend/src/views/ExportView.vue`
- 这些页面直接使用的前端类型和局部组件

## 禁止修改

- `frontend/src/router/index.ts`
- `frontend/src/components/ProjectWorkspaceNav.vue`
- `frontend/src/styles/global.css`
- `backend/`
- Dify、Kimi、PPT Harness 和数据库代码

## 验收标准

- 页面路径与五阶段命名一致：课程大纲、教案设计、PPT 成果。
- 生成中、生成失败、无成果和已生成状态均有清晰反馈。
- PPT 预览与导出使用现有受保护后端接口，不暴露内部文件路径。
- 不再出现“成果预览”和“成果导出”两个割裂的重复主入口。
- `npm.cmd ci` 与 `npm.cmd run build` 通过。

## 回填格式

完成后回填：修改文件、接口验证、构建命令、commit、遗留问题。暂不发送 GitHub 消息，由项目负责人统一分发和合并。
