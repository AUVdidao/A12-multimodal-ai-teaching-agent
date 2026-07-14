# A12 三角色课件协同平台 P0 需求基线

## 1. 变更性质

本需求将系统从教师个人备课工具扩展为单学校、单级审批的协同平台：

```text
课程负责人分配备课任务
-> 教师使用现有 M1-M4 链路生产固定版本
-> 课程负责人审批
-> 已审批版本发布给明确班级
-> 学生只读学习并围绕版本提问
-> 教师回答，学生确认解决
```

这是系统边界、数据归属和权限模型的变更，不以零散前端角色判断实现。后端必须对每个受保护请求执行身份和权限校验。

## 2. P0 范围

### 2.1 角色

- `TEACHER`：执行备课任务、关联项目、提交固定版本、查看审批结果、回答学生问题。
- `LEADER`：在授权课程内分配任务、审批、发布、撤回、监管问题。
- `STUDENT`：访问分发给本人所在班级的已发布版本、提问、追问、标记解决。
- 一个用户可拥有多个角色，但每个会话必须选择一个当前活动角色。

### 2.2 业务闭环

1. 三角色登录、当前身份切换和后端鉴权。
2. 负责人创建备课任务，教师开始并关联教学项目。
3. 教师将一个不可变成果版本提交审批。
4. 负责人通过、退回修改或驳回；审批记录只追加不覆盖。
5. 负责人将已审批版本立即发布给至少一个班级。
6. 目标班级学生只读访问；非目标学生、撤回后访问返回 `403`。
7. 学生针对版本、页面或章节提问，教师回答，学生可追问和确认解决。
8. 任务、审批、发布和问答关键动作记录操作者与时间。

### 2.3 明确排除

- 多级审批、会签、复杂组织树和多学校多租户。
- 考试、成绩、作业批改、直播、完整学习进度和家长端。
- 短信、邮件和外部消息渠道。
- AI 未经教师确认直接回答学生。
- 本阶段不让负责人直接编辑教师课件正文。

## 3. 安全基线

### 3.1 身份方案

- 密码使用 BCrypt 哈希，不存储明文。
- 登录返回高熵随机 Bearer token；数据库只保存 token 的 SHA-256 摘要。
- 会话记录用户、活动角色、到期时间、撤销时间和最近使用时间。
- 切换身份只能选择账号已绑定的角色。
- 公开注册只允许创建 `STUDENT`，不得由请求体指定 `LEADER` 或 `TEACHER`。
- 教师、负责人和演示班级数据由受控初始化或后续管理接口创建。

### 3.2 访问规则

- `/api/health`、登录和学生注册为公开接口。
- 现有项目生产链路仅允许活动角色为 `TEACHER` 的已认证用户访问。
- 新 `/api/v1/**` 接口先认证，再在业务服务中校验角色、课程、任务、项目、班级和版本归属。
- 前端隐藏按钮只是体验控制，不能替代后端鉴权。
- `401` 表示未认证或会话失效；`403` 表示已认证但无权执行。

## 4. 数据对象

| 对象 | 核心责任 |
| --- | --- |
| `AppUser` | 用户名、显示名、密码摘要、启用状态 |
| `UserRoleAssignment` | 用户与 `TEACHER/LEADER/STUDENT` 的多对多关系 |
| `UserSession` | 可撤销会话及活动角色 |
| `Course` | 单学校课程边界及负责人 |
| `ClassGroup` | 班级或学生组 |
| `ClassMember` | 学生与班级关系 |
| `TeachingTask` | 负责人分配给教师的备课任务 |
| `ApprovalRequest` | 固定 `ArtifactVersion` 的审批申请 |
| `ApprovalRecord` | 每次不可覆盖的审批动作与意见 |
| `Publication` | 已审批版本的发布配置与状态 |
| `PublicationAudience` | 发布面向的明确班级 |
| `StudentQuestion` | 与发布、版本、页码/章节关联的原始问题 |
| `QuestionReply` | 教师回答或学生追问 |
| `AuditLog` | 关键业务动作审计 |

现有 `Project` 继续作为教师生产容器；现有 `ArtifactVersion` 继续作为审批和发布的版本边界，不重建第二套课件模型。

## 5. 状态模型

```text
TeachingTaskStatus:
DRAFT, ASSIGNED, IN_PROGRESS, SUBMITTED, REVISION_REQUIRED,
COMPLETED, CANCELLED

ApprovalStatus:
PENDING, IN_REVIEW, APPROVED, REVISION_REQUIRED, REJECTED, CANCELLED

PublicationStatus:
DRAFT, SCHEDULED, PUBLISHED, EXPIRED, WITHDRAWN

QuestionStatus:
OPEN, ANSWERED, FOLLOW_UP, RESOLVED, CLOSED
```

逾期是根据 `dueAt` 计算的独立布尔状态，不覆盖任务业务状态。

## 6. 不可变规则

1. 审批请求必须绑定一个固定 `ArtifactVersion`。
2. 提交审批后该版本锁定；修改必须产生新版本。
3. 同一用户不得审批自己创建或提交的版本。
4. 只有 `APPROVED` 版本可发布。
5. 发布对象必须至少包含一个明确班级。
6. 撤回只改变发布状态，不删除审批、访问或问答历史。
7. 学生问题必须绑定原发布和原版本，版本更新不迁移历史问题。
8. 退回、驳回和关闭问题必须填写原因。

## 7. P0 接口命名

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/auth/me
POST /api/v1/auth/switch-role
POST /api/v1/auth/logout

POST /api/v1/teaching-tasks
GET  /api/v1/teaching-tasks
GET  /api/v1/teaching-tasks/{taskId}
PUT  /api/v1/teaching-tasks/{taskId}
POST /api/v1/teaching-tasks/{taskId}/start
PUT  /api/v1/teaching-tasks/{taskId}/project
POST /api/v1/teaching-tasks/{taskId}/cancel

POST /api/v1/projects/{projectId}/versions/{versionId}/approval-requests
GET  /api/v1/approval-requests
GET  /api/v1/approval-requests/{approvalId}
POST /api/v1/approval-requests/{approvalId}/actions
POST /api/v1/approval-requests/{approvalId}/cancel

POST /api/v1/publications
GET  /api/v1/publications
PUT  /api/v1/publications/{publicationId}/withdraw
GET  /api/v1/student/learning-tasks
GET  /api/v1/student/learning-tasks/{publicationId}

POST /api/v1/publications/{publicationId}/questions
GET  /api/v1/questions
GET  /api/v1/questions/{questionId}
PUT  /api/v1/questions/{questionId}
POST /api/v1/questions/{questionId}/replies
PUT  /api/v1/questions/{questionId}/resolve
PUT  /api/v1/questions/{questionId}/close
```

## 8. 分阶段交付

| 阶段 | 可独立验收的结果 |
| --- | --- |
| C0 | 身份、注册、登录、多角色切换、401/403、教师旧链路受保护 |
| C1 | 课程/班级基础数据、负责人分配任务、教师开始与关联项目 |
| C2 | 固定版本提交、退回、新版本再提交、禁止自审、审批通过 |
| C3 | 班级发布、目标学生只读、下载/提问开关、撤回 |
| C4 | 学生问题、教师回答、学生追问与解决、问题原版本关联 |
| C5 | 三角色前端、Docker 端到端、越权矩阵、API 与最终验收报告 |

每一阶段使用独立分支、提交、PR、主线复验和一份外部审计日志；日志只追加可复验事实。

## 9. 当前实施状态

### C0 身份与权限基础

`collaboration-rbac-foundation` 已完成并通过分支验收：

- 学生公开注册、登录、当前身份、同 token 角色切换和退出接口已实现；
- BCrypt 密码摘要、SHA-256 token 摘要、到期与撤销会话已持久化；
- 公开注册无法通过请求体获得教师或负责人角色；
- 原教师生产接口在启用安全配置时仅允许活动角色 `TEACHER`；
- Docker 演示模式提供教师、负责人、学生和多角色受控初始化账号；
- 演示前端可快捷填入账号，非演示构建默认隐藏该能力；
- 后端测试 `140` 项通过，前端生产构建通过；
- Docker 冒烟验证匿名 `401`、学生越权 `403`，并以教师 token 完成 M1-M3 动态项目 `40`；
- 桌面 `1440x1000` 与手机 `390x844` 登录页通过真实浏览器检查，控制台无错误。

C1-C6 仍按本文件第 8 节推进。角色工作台、任务、审批、发布、问答和当前无效导出页在对应真实后端契约完成前不得使用静态数字冒充已实现能力。
