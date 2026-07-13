# A12 UI V6 前后端集成验收报告

## 1. 验收范围

本报告记录 UI V6 八个演示页面、对应聚合后端接口、M1/M2 真实数据联调、响应式体验和 Docker 更新结果。

- 验收时间：2026-07-14 01:28（Asia/Shanghai）
- 开发分支：`ui-v6-fullstack-integration`
- 分支基线：`48368ee`
- 运行模式：Mock AI + H2 文件数据库
- 演示地址：`http://localhost:8081`
- 后端地址：`http://localhost:8080`

本次覆盖页面：

1. 教师工作台
2. 教学项目列表
3. 项目概览
4. 教学需求与澄清
5. 需求摘要确认
6. 参考资料与原型解析
7. 本地知识检索
8. 教学意图确认

M3 教学内容生成、M4 预览与导出仍为后续阶段。本次页面对未实现能力使用明确的不可用状态，没有使用伪造结果冒充真实能力。

## 2. 前端集成结果

结果：通过。

- 八个目标页面均从后端读取真实项目、需求、对话、摘要、资料、知识片段和教学意图数据。
- 已删除 `frontend/src/mock/demo.ts` 与 `frontend/src/mock/projectPresentation.ts`，源码不再引用演示 Mock 数据模块。
- 工作台、项目列表和项目概览使用聚合接口，避免页面自行拼接大量底层请求。
- 需求澄清支持结构化需求编辑、对话保存、主动追问、清空对话和摘要入口。
- 需求摘要支持编辑、保存、确认，并展示真实来源和锁定状态。
- 资料工作台支持上传、用途绑定、解析、下载、索引及解析摘要预览。
- 知识检索只展示当前项目真实知识片段、匹配度、命中理由和来源资料。
- 教学意图支持生成、编辑、保存、确认，并展示真实依据证据。
- 桌面端保持目标图的产品壳层、信息层级和工作台密度。
- 390 x 844 移动端使用紧凑顶部主导航；面板标题、筛选控件和步骤条不会互相覆盖。

## 3. 后端接口结果

结果：通过。

新增或补齐的 UI V6 聚合接口：

```text
GET    /api/workspace/overview
GET    /api/workspace/projects
GET    /api/projects/{projectId}/workspace-overview
GET    /api/projects/{projectId}/requirements/workspace
DELETE /api/projects/{projectId}/dialogues
GET    /api/projects/{projectId}/requirement-summaries/workspace
GET    /api/projects/{projectId}/materials/workspace
POST   /api/projects/{projectId}/knowledge/workspace-search
GET    /api/projects/{projectId}/teaching-intents/workspace
PUT    /api/projects/{projectId}/teaching-intents/{intentId}/workspace
```

接口字段、示例和状态语义见 [UI V6 后端接口文档](../api/ui-v6-backend-api.md)。

资料上传策略通过 `uploadEnabled` 明确返回当前是否允许上传，避免前端错误使用摘要门禁字段。单文件上限已与目标页面统一为 200 MB。

## 4. 自动化验证

### 4.1 后端

命令：

```powershell
D:\pri_work\.tools\apache-maven-3.9.9\bin\mvn.cmd -B -ntp test
```

结果：

```text
Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试环境使用 `jdbc:h2:mem:a12_test`，不读取本地开发 H2 文件库。

### 4.2 前端

命令：

```powershell
cd frontend
npm.cmd run build
```

结果：`vue-tsc --noEmit` 与 `vite build` 均通过。仅保留第三方 PURE 注释和主包大于 500 kB 的非阻断警告。

### 4.3 Docker

命令：

```powershell
docker compose build --pull=false
docker compose up -d
powershell -ExecutionPolicy Bypass -File scripts\docker-smoke-test.ps1
```

结果：通过。最终冒烟数据为：

```text
projectId=32
requirementId=46
summaryId=22
materialId=11
intentId=9
sessionId=project-32-clarification
```

冒烟覆盖项目创建、模式保存、需求保存、完整度检查、主动追问、对话、摘要、资料上传与解析、知识检索、教学意图更新与确认，以及所有 UI V6 聚合接口。

## 5. 真实浏览器验收

使用 Playwright 对八个页面执行桌面端 `1680 x 945` 和移动端 `390 x 844` 截图验收。

- 页面控制台错误：0
- 页面运行时异常：0
- 页面级横向溢出：0
- 桌面端八页：通过
- 移动端八页：通过
- 移动端侧栏首屏占用、需求标题挤压、意图步骤重叠：已修复并复验

截图保存在本地忽略目录 `output/playwright/`，不作为仓库源码提交。

## 6. Docker 运行状态

验收完成后未关闭容器：

```text
backend  Up  0.0.0.0:8080->8080/tcp
frontend Up  0.0.0.0:8081->80/tcp
```

后端与 Nginx 日志未发现启动错误，前端 `/api` 反向代理正常。

## 7. 验收结论

UI V6 的 M1/M2 演示页面、真实后端数据契约、Docker 镜像和浏览器体验均已达到集成分支提交条件。当前可以演示从项目工作台进入需求澄清、摘要确认、资料解析、知识检索到教学意图确认的完整流程。

后续工作应从 M3 内容生成开始，不应将本报告扩展为 PPT、Word、互动内容生成或导出能力已完成的证明。
