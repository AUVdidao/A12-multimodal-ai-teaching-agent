# A12 项目入口说明

更新基线：`9422c0ed08a63b99591b2368e5dcca8257048040`

## 目录职责

- `backend/`：Spring Boot 业务 API、权限、持久化、AI 工作流编排、模型调用与成果管理
- `frontend/`：Vue 3 教师、教研负责人和学生工作台
- `file-parser-service/`：PDF、DOCX、PPTX、TXT、MD 等文件正文解析
- `file-generator-service/`：DOCX、互动 HTML、ZIP 等成果文件生成
- `ppt-harness/`：PPT 任务执行、模板编排、质量门禁和任务状态
- `ppt-skill-runner/`：PPTX 文件渲染执行器
- `docs/`：接口、设计、测试和部署文档
- `scripts/`：本地验证与部署脚本

## AI 架构

系统不再使用 Dify。`AIWorkflowGatewayRouter` 在 `KIMI` 和 `MOCK` 两种 Provider 之间路由。真实模式由 `KimiAIWorkflowGateway` 调用 Kimi，并对模型 JSON 执行解析、一次修复、DTO 映射和业务结构校验。

知识检索仍以本地候选片段为事实来源；当候选片段为空时，系统不会让模型补造来源。PPT Harness 只负责复杂成果执行，不承担需求澄清、项目状态或权限职责。

## 主流程

登录 → 创建项目 → 需求澄清 → 摘要确认 → 资料上传解析 → 本地知识检索 → 教学意图确认 → 生成方案 → 内容生成 → 预览修改 → 版本/审批/发布 → 学生学习与问答 → 教学分析。
