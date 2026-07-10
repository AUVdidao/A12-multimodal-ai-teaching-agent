# M1 集成检查清单

## Git 与范围

- [x] 基于最新 `origin/main` 创建独立工作分支
- [x] 未整分支合并旧 TA-007 实现
- [x] 未实现 M2 资料上传、RAG 或生成能力
- [x] 未引入真实 Dify、Redis、Spring Cloud 或 MyBatis-Plus

## 后端

- [x] Requirement 每次保存形成新版本
- [x] Latest 返回当前项目最新版本
- [x] Clarification 复用 TA-008 接口
- [x] AI/TEACHER 消息复用 TA-009 接口
- [x] Summary 支持 generate/latest/update/confirm
- [x] 已确认摘要不可修改，重复确认幂等
- [x] 项目与摘要归属校验生效
- [x] 测试使用内存 H2

## 前端

- [x] 项目级需求路由可刷新恢复
- [x] 需求表单保存与 latest 回显
- [x] 缺失字段使用对应输入控件
- [x] AI 问题和教师补充写入对话历史
- [x] 相同 AI 问题不会重复写入
- [x] 需求完整后才能进入摘要页
- [x] 摘要可编辑、保存和确认
- [x] 已确认状态刷新后恢复
- [x] M2 仅显示下一阶段入口，不伪造完成状态

## 验证

- [x] 后端完整测试通过：66 tests，0 failures/errors/skipped
- [x] 前端生产构建通过
- [x] Docker config/build/up/smoke/log/down 通过
- [x] 真实浏览器完整链路通过
- [x] Changed Files 与安全检查通过
- [ ] 合并后 `main` 全量复验通过

合并后复验项在 PR merge 完成后执行，结果记录在任务最终报告中。
