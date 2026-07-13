# A12 UI V6 Backend API Contract

本文档定义 UI V6 八个高保真页面与当前 Spring Boot 后端之间的联调契约。接口以真实持久化数据为来源；页面聚合数据由后端根据项目、需求、资料、知识片段和教学意图状态确定性计算，不使用前端演示假数据。

## 1. 基线与范围

- 后端实现分支：`backend-ui-v6-api-support`
- 后端基线：`main@48368ee`
- 前端参考分支：
  - `a12-7-ta-007-requirement-input-v2@3d661c1`
  - `a12-8-ta-008-clarification-mock@ac040b6`
- 前端参考分支中的旧接口保持兼容；新页面应逐步用本文的页面聚合接口替换 `demo.ts` 或组件内静态数据。
- 本轮覆盖 M1/M2 的读取、编辑和确认能力，不伪造全局搜索、通知中心、用户账号、搜索历史、内容生成、版本导出等尚未实现的模块。

## 2. 通用约定

### 2.1 服务地址

本地开发：`http://localhost:8080`

Docker 前端反向代理：`http://localhost:8081/api`

前端应优先使用相对路径 `/api/...`，由 Vite 或 Nginx 转发到后端。

### 2.2 统一响应

除资料下载外，接口均返回 `ApiResponse<T>`：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": "2026-07-13T08:00:00Z"
}
```

HTTP 状态与错误码保持一致：

| HTTP | 场景 |
| --- | --- |
| `400` | 参数缺失、类型错误、非法枚举或业务前置条件不满足 |
| `404` | 项目、资料、摘要、教学意图等资源不存在或不属于当前项目 |
| `409` | 当前状态不允许重复生成、确认、解析或索引 |
| `413` | 上传文件超过配置上限 |
| `500` | 文件存储或未预期的服务端错误 |
| `503` | AI Provider 不可用 |

### 2.3 时间、分页与枚举

- 时间字段为 ISO-8601，例如 `2026-07-13T15:20:00`。
- 分页从 `page=0` 开始。
- 项目列表 `size` 为 `1..100`；知识检索 `size` 为 `1..50`。
- 前端必须按接口返回的 `stageLabel`、`nextAction`、`actionPath` 展示流程状态，不自行推断阶段。

## 3. 八个页面接口映射

| 目标页面 | 页面聚合接口 | 主要写接口 |
| --- | --- | --- |
| 01 教师工作台 | `GET /api/workspace/overview` | 无；任务跳转使用返回的 `actionPath` |
| 02 教学项目列表 | `GET /api/workspace/projects` | `POST /api/projects`、`PUT /api/projects/{projectId}` |
| 03 项目概览 | `GET /api/projects/{projectId}/workspace-overview` | 沿用各阶段原子接口 |
| 04 教学需求与澄清 | `GET /api/projects/{projectId}/requirements/workspace` | 需求、澄清、对话与清空接口 |
| 05 需求摘要确认 | `GET /api/projects/{projectId}/requirement-summaries/workspace` | 摘要生成、编辑、确认接口 |
| 06 资料工作台 | `GET /api/projects/{projectId}/materials/workspace` | 上传、用途、解析、重试与索引接口 |
| 07 本地知识检索 | `POST /api/projects/{projectId}/knowledge/workspace-search` | 资料索引接口 |
| 08 教学意图确认 | `GET /api/projects/{projectId}/teaching-intents/workspace` | 生成、结构化编辑、确认接口 |

## 4. 页面聚合接口

### 4.1 教师工作台

`GET /api/workspace/overview`

返回：

- `metrics`：项目数、进行中项目数、派生待办数、资料数、已确认意图数、生成成果数。
- `continueProjects`：最近更新且未定稿的项目。
- `pendingTasks`：根据项目当前真实状态推导的下一步任务，`derived=true`。
- `recentActivities`：需求、资料、意图、生成成果等真实更新时间线。
- `suggestions`：根据缺失步骤生成的可执行建议。

关键结构：

```json
{
  "metrics": {
    "projectCount": 8,
    "activeProjectCount": 5,
    "pendingTaskCount": 5,
    "materialCount": 12,
    "confirmedIntentCount": 2,
    "generatedArtifactCount": 0
  },
  "continueProjects": [],
  "pendingTasks": [],
  "recentActivities": [],
  "suggestions": [],
  "generatedAt": "2026-07-13T15:20:00"
}
```

说明：当前没有独立的任务、协作动态、通知和用户服务，因此这些区域只返回可由项目实体可靠推导的数据，不返回高保真图中的虚构教师或截止时间。

### 4.2 教学项目列表

`GET /api/workspace/projects?query=&stage=ALL&page=0&size=10&sort=UPDATED_DESC`

参数：

| 参数 | 默认值 | 可选值/说明 |
| --- | --- | --- |
| `query` | 空 | 匹配项目名、课程、章节、授课对象和描述 |
| `stage` | 空 | `ALL` 或下方阶段值；也接受 `ProjectStatus` |
| `page` | `0` | 从 0 开始 |
| `size` | `10` | `1..100` |
| `sort` | `UPDATED_DESC` | `UPDATED_DESC`、`UPDATED_ASC`、`PROGRESS_DESC`、`NAME_ASC` |

阶段值：

```text
REQUIREMENT_CLARIFYING
REQUIREMENT_CONFIRMED
MATERIAL_ANALYZING
KNOWLEDGE_INDEXED
INTENT_CONFIRMED
CONTENT_GENERATED
FINALIZED
```

每个 `items[]` 都包含项目基础信息、`stage`、`stageLabel`、`progress`、`nextAction`、`actionPath`、统计数量和更新时间。进度由已保存的需求、确认摘要、资料解析、知识索引、意图确认、成果和最终版本确定性计算。

### 4.3 项目概览

`GET /api/projects/{projectId}/workspace-overview`

返回：

- `project`：完整 `ProjectBrief`。
- `timeline`：项目创建、需求澄清、需求确认、资料解析、知识增强、教学意图、内容生成、成果导出。
- `metrics`：整体进度、各类成果、资料、知识片段、版本和导出数量。
- `recentActivities`：最新真实活动。
- `quickActions`：页面快捷入口及 `enabled` 状态。

未实现成果生成时，PPT、DOCX、互动内容、版本和导出计数会返回 `0`，不会用设计图中的样例数字填充。

### 4.4 教学需求与澄清工作区

`GET /api/projects/{projectId}/requirements/workspace`

返回：

- `latestRequirement`：最近保存的需求，可为 `null`。
- `dialogues`：按时间升序排列的项目对话。
- `completeness`：九项关键信息的收集数量、百分比和逐项状态。
- `suggestedQuestions`：针对缺失项生成的确定性追问。
- `canGenerateSummary`：已收集至少 6 项时为 `true`；最终确认仍由摘要接口校验必填字段。

九项完整度字段：

```text
topic
teachingGoals
audience
baselineLevel
lessonDuration
keyDifficulties
stylePreference
interactionType
outputTypes
```

### 4.5 需求摘要确认工作区

`GET /api/projects/{projectId}/requirement-summaries/workspace`

返回 `project`、最新 `summary`、`source`、`editable`、`canConfirm` 和 `nextStageCapabilities`。尚未生成摘要时，`summary=null`，前端应显示生成入口而不是模拟摘要。

### 4.6 资料工作台

`GET /api/projects/{projectId}/materials/workspace`

返回：

- `uploadPolicy`：200 MB 上限、支持扩展名、是否要求先确认摘要。
- `purposeOptions`：后端允许的用途及标签说明。
- `statistics`：总数、解析中、解析成功、失败、已索引数量。
- `materials`：资料元数据、用途、解析状态、下载地址和解析预览。

默认支持：

```text
PDF DOCX PPT PPTX XLSX TXT MD PNG JPG JPEG MP4
```

允许的用途：

```text
TEXTBOOK_BASIS
CASE_MATERIAL
EXERCISE_SOURCE
KNOWLEDGE_SUPPLEMENT
IMAGE_ASSET
```

默认/生产上限为 200 MB。测试 profile 保持 20 MB，用于快速验证超限行为。

### 4.7 本地知识检索

`POST /api/projects/{projectId}/knowledge/workspace-search`

请求：

```json
{
  "query": "机器学习中的过拟合是什么，如何解决？",
  "materialId": null,
  "matchMode": "PRECISE",
  "caseSensitive": false,
  "page": 0,
  "size": 10
}
```

规则：

- `matchMode`：`PRECISE` 或 `BROAD`，`EXACT` 会兼容为 `PRECISE`。
- `materialId` 不为空时，只检索该项目内指定资料。
- 返回命中的真实知识片段，不补足固定数量。
- `scorePercent` 为确定性加权分数；关键词、标题、正文和资料用途会进入评分。
- `algorithm` 和 `hitReason` 可直接展示以说明结果来源。
- `prototype=true` 表示当前为本地确定性原型检索，不是向量数据库召回。

响应核心结构：

```json
{
  "projectId": 1,
  "query": "过拟合",
  "matchMode": "PRECISE",
  "caseSensitive": false,
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1,
  "hits": [
    {
      "chunkId": 8,
      "materialId": 3,
      "chunkNo": 1,
      "scorePercent": 95,
      "title": "过拟合的定义与解决方法",
      "content": "...",
      "sourceFilename": "机器学习基础.pdf",
      "sourceLocation": "知识片段 #1",
      "keywords": ["过拟合", "正则化"],
      "usageTypes": ["TEXTBOOK_BASIS"],
      "hitReason": "命中关键词「过拟合」，标题匹配，正文内容匹配。"
    }
  ],
  "algorithm": "确定性精确短语、关键词、标题与正文加权",
  "prototype": true
}
```

### 4.8 教学意图确认工作区

`GET /api/projects/{projectId}/teaching-intents/workspace`

返回：

- `project`：项目和当前阶段。
- `intent`：最新教学意图，可为 `null`。
- `options`：生成目标、内容依据、教学形式、输出类型的可选项。
- `canGenerate`、`canEdit`、`canConfirm`：由真实前置条件和状态决定。
- `evidenceCount`：教学意图引用的真实资料/知识证据数量。

结构化编辑：

`PUT /api/projects/{projectId}/teaching-intents/{intentId}/workspace`

```json
{
  "generationGoals": ["知识理解", "概念掌握", "应用能力"],
  "primaryBasis": "已确认教学需求",
  "supplementalBasis": ["本地知识库", "上传资料证据"],
  "targetAudience": "大学本科一年级",
  "totalHours": 16,
  "teachingFormat": "线上线下混合式教学",
  "outputTypes": ["教学大纲", "教学PPT", "课堂活动", "习题与测评"],
  "stylePreference": "案例为主、循序渐进",
  "notes": "补充医疗领域应用案例"
}
```

`generationGoals`、`primaryBasis`、`targetAudience`、`teachingFormat` 和 `outputTypes` 必填；`totalHours` 为 `1..1000`。

## 5. 原子写接口

页面聚合接口负责一次加载页面；以下原子接口负责保存、状态变更和文件操作。

### 5.1 项目

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/projects` | 创建项目 |
| `GET` | `/api/projects` | 兼容旧版项目列表 |
| `GET` | `/api/projects/{projectId}` | 项目详情 |
| `PUT` | `/api/projects/{projectId}` | 更新基础信息 |
| `GET` | `/api/model-modes` | 模式列表 |
| `GET` | `/api/projects/{projectId}/model-mode` | 当前模式 |
| `PUT` | `/api/projects/{projectId}/model-mode` | 保存模式 |

创建/编辑请求：

```json
{
  "projectName": "人工智能基础概念与应用",
  "courseName": "人工智能基础",
  "chapterTitle": "人工智能的基本概念与发展历程",
  "targetStudents": "大学本科一年级",
  "lessonDuration": 90,
  "description": "面向非计算机专业的概念课"
}
```

### 5.2 教学需求、澄清和对话

保存需求：`POST /api/projects/{projectId}/requirements`

```json
{
  "gradeLevel": "大学一年级",
  "subject": "人工智能基础",
  "topic": "人工智能基础概念与应用",
  "baselineLevel": "有编程基础，对 AI 了解不多",
  "lessonDuration": "2课时",
  "teachingGoals": "理解基本概念、发展历程和典型应用",
  "keyPoints": "AI 定义、发展历程、典型应用",
  "difficultPoints": "机器学习与深度学习的区别",
  "stylePreference": "活泼，案例为主",
  "interactionType": "课堂问答互动",
  "outputTypes": ["PPT", "DOCX", "INTERACTION"],
  "rawRequirementText": "希望做一节人工智能导论课"
}
```

`topic` 与 `rawRequirementText` 至少填写一个。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/projects/{projectId}/requirements/latest` | 刷新回显最近需求 |
| `POST` | `/api/projects/{projectId}/clarification/check` | 返回缺失字段 |
| `POST` | `/api/projects/{projectId}/clarification/questions` | 返回缺失字段与追问 |
| `POST` | `/api/projects/{projectId}/dialogues` | 保存教师/AI 消息 |
| `GET` | `/api/projects/{projectId}/dialogues` | 查询项目对话 |
| `GET` | `/api/dialogues/{sessionId}` | 查询会话对话 |
| `DELETE` | `/api/projects/{projectId}/dialogues` | 清空项目对话，返回删除数量 |

对话写入请求：

```json
{
  "sessionId": "project-1-requirement",
  "sender": "TEACHER",
  "content": "面向大学一年级计算机专业学生。",
  "roundNo": 2
}
```

### 5.3 需求摘要

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/projects/{projectId}/requirement-summaries/generate` | 从最近需求和对话生成草稿 |
| `GET` | `/api/projects/{projectId}/requirement-summaries/latest` | 最近摘要 |
| `PUT` | `/api/projects/{projectId}/requirement-summaries/{summaryId}` | 编辑草稿 |
| `POST` | `/api/projects/{projectId}/requirement-summaries/{summaryId}/confirm` | 确认并推进项目状态 |

编辑字段与需求字段一致，包含新增的 `baselineLevel` 和 `interactionType`。确认要求至少具备年级、学科、主题、课时、教学目标和输出类型。

### 5.4 资料、解析和索引

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/projects/{projectId}/materials` | `multipart/form-data` 上传，字段为 `file`、可选 `description` |
| `GET` | `/api/projects/{projectId}/materials` | 项目资料列表 |
| `GET` | `/api/projects/{projectId}/materials/{materialId}` | 资料详情 |
| `GET` | `/api/projects/{projectId}/materials/{materialId}/download` | 二进制下载，不使用统一响应 |
| `PUT` | `/api/projects/{projectId}/materials/{materialId}/usages` | 保存用途 |
| `GET` | `/api/projects/{projectId}/materials/{materialId}/usages` | 用途回显 |
| `POST` | `/api/projects/{projectId}/materials/{materialId}/parse` | 原型解析 |
| `GET` | `/api/projects/{projectId}/materials/{materialId}/parse-result` | 解析结果 |
| `POST` | `/api/projects/{projectId}/materials/{materialId}/parse/retry` | 失败重试 |
| `POST` | `/api/projects/{projectId}/materials/{materialId}/index` | 建立本地知识片段 |

用途请求：

```json
{
  "usageTypes": ["TEXTBOOK_BASIS", "KNOWLEDGE_SUPPLEMENT"],
  "note": "用于核心概念和课后拓展"
}
```

### 5.5 教学意图

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/projects/{projectId}/teaching-intents/generate` | 根据确认摘要和知识证据生成草稿 |
| `GET` | `/api/projects/{projectId}/teaching-intents/latest` | 兼容旧版详情 |
| `PUT` | `/api/projects/{projectId}/teaching-intents/{intentId}` | 兼容旧版自由文本编辑 |
| `PUT` | `/api/projects/{projectId}/teaching-intents/{intentId}/workspace` | UI V6 结构化编辑 |
| `POST` | `/api/projects/{projectId}/teaching-intents/{intentId}/confirm` | 确认教学意图 |

## 6. 前端接入顺序

1. PR #7 保留已有项目、需求、对话 API 封装，不回退这些已工作的原子接口。
2. 教师工作台、项目列表、项目概览先接三个聚合读取接口，移除对应静态统计与样例项目。
3. 需求澄清页加载 `requirements/workspace`，保存仍调用需求和对话原子接口；保存后重新获取聚合数据。
4. 摘要、资料、检索、意图页面按同样方式使用“聚合读取 + 原子写入”。
5. PR #8 当前主要是壳层与路由；应复用同一契约，不再创建第二套字段名。
6. 前端提交前必须确认没有把高保真图中的固定数字、固定教师、固定项目、固定检索结果当作后端数据。

## 7. 暂不提供的接口

以下视觉元素属于后续模块，本轮不提供伪接口：

- 顶栏全局搜索、搜索历史和快捷键搜索服务。
- 通知、未读数量、帮助中心、用户头像与账号资料。
- 独立任务管理、协作成员、回收站和最近访问。
- 真实 OCR、视频转写、向量检索和 Dify Cloud 调用。
- PPT、DOCX、互动内容生成、版本管理和导出。
- 资料库跨项目选择、模板中心、教学分析和学情洞察。

前端应对这些入口使用明确的禁用状态或“后续阶段”提示，不得用静态数据伪装成已完成能力。

## 8. 验证

后端全量测试：

```powershell
cd backend
D:\pri_work\.tools\apache-maven-3.9.9\bin\mvn.cmd -B -ntp test
```

当前实现验证结果：

```text
Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

UI V6 新增覆盖包括：工作台/项目列表、九项需求完整度与清空对话、扩展需求到摘要、资料与真实知识检索、项目概览、结构化教学意图、错误响应，以及 XLSX/MD/MP4 上传。
