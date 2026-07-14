# A12 M3 内容生成与成果预览验收报告

## 1. 记录信息

| 项目 | 内容 |
| --- | --- |
| 验收日期 | 2026-07-14 |
| 基线提交 | `526387f`（UI V6 全栈基线合入 main） |
| 开发分支 | `m3-content-generation-complete` |
| 覆盖任务 | TA-016 至 TA-020，并包含 M2 正文解析加固 |
| AI Provider | `MOCK`，未发起真实 Dify 网络请求 |
| 当前状态 | PR #10 已合入 `main`，合并提交 `24e463d`；主线复验完成 |

## 2. 验收范围

本阶段完成以下闭环：

```text
已确认教学意图
-> 创建生成方案
-> 教师编辑并确认方案
-> 生成 PPT / 教案 / 互动问答规范化内容
-> 创建 v1 成果版本
-> 桌面端与移动端真实预览
```

M3 不生成伪 `.pptx` 或 `.docx` 文件。真实 Office、HTML 和 ZIP 文件生成，自然语言修改、版本恢复及最终定稿属于 M4。

## 3. 后端实现

### 3.1 生成方案

- 只有最新已确认教学意图存在时才允许创建方案。
- 方案包含 PPT 大纲、教案大纲和互动方案，支持保存教师编辑。
- 已确认方案不可继续修改；重复确认保持幂等。
- 方案记录关联项目和教学意图，并持久化 Provider、创建时间和更新时间。

### 3.2 结构化成果

- PPT：至少 7 页，覆盖封面、目录、教学目标、知识内容、案例、互动和总结。
- 教案：覆盖课程信息、教学目标、重点、难点、方法、过程、课堂活动、课后作业和资源说明。
- 互动：3 道单选题，每题包含选项、正确答案和解析。
- 首次生成创建 `v1`，三类成果关联同一版本和生成方案。
- 对同一方案重复生成返回原成果 ID，不创建重复版本或重复成果。
- 规范化内容使用 `schemaVersion=1`，为 M4 文件渲染提供稳定输入。

### 3.3 接口

```text
GET  /api/projects/{projectId}/generation/workspace
POST /api/projects/{projectId}/generation-plans
GET  /api/projects/{projectId}/generation-plans/latest
PUT  /api/projects/{projectId}/generation-plans/{planId}
POST /api/projects/{projectId}/generation-plans/{planId}/confirm
POST /api/projects/{projectId}/artifacts/generate
GET  /api/projects/{projectId}/artifacts
GET  /api/projects/{projectId}/artifacts/{artifactId}
```

正式契约见 `docs/api/m3-generation-api.md`。

## 4. M2 正文解析加固

为保证 M3 依据真实资料而不是文件名生成内容，本阶段同时加固了原型解析：

| 文件类型 | 当前行为 |
| --- | --- |
| TXT / MD | 严格 UTF-8 正文读取 |
| PDF | PDFBox 页面文本提取 |
| DOCX | Apache POI 段落与表格提取 |
| PPTX | Apache POI 页面、表格与组合图形文本提取 |
| 图片 | 明确提示未启用 OCR，不伪造正文 |
| 视频 | 明确提示未启用转写，不伪造正文 |

解析器限制输入大小、提取字符数、PDF 页数、PPTX 页数和 OOXML 解压规模，并拒绝加密、损坏、空文件和危险归档路径。

## 5. 前端实现与交互

- 生成方案页读取真实生成工作区，支持创建、编辑、确认和生成。
- 已确认方案与已有成果状态可刷新恢复。
- 成果预览按 PPT、教案、互动内容三个标签展示真实后端数据。
- PPT 提供 7 页缩略图与稳定 16:9 主预览。
- 教案按 9 个章节展示。
- 互动题支持选择、提交、正确/错误反馈、参考答案、解析和重新作答。
- 移动端成果标题可换行，三个成果标签固定三等分，不再出现文字截断或页面横向溢出。
- 项目导航已开放内容生成和成果预览，成果导出仍明确标记为后续阶段。

## 6. 自动化验证

### 6.1 后端

```text
命令：mvn test（本机使用已安装 Maven launcher）
结果：Tests run: 136, Failures: 0, Errors: 0, Skipped: 0
结论：BUILD SUCCESS
```

新增测试覆盖生成门禁、方案编辑确认、三类成果结构、项目隔离、重复生成幂等，以及 TXT/MD/PDF/DOCX/PPTX、损坏文件和诚实降级解析。

### 6.2 前端

```text
命令：npm.cmd run build
结果：vue-tsc --noEmit 与 vite build 通过
```

仅保留既有第三方纯注释和大 chunk 警告，不影响构建产物；未发现 TypeScript 错误。

### 6.3 静态检查

```text
命令：git diff --check
结果：通过；仅显示 Git 的 LF/CRLF 转换提示，无空白错误
```

## 7. Docker 端到端验收

已执行：

```text
docker compose config
docker compose up -d --build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/docker-smoke-test.ps1
```

最终完整冒烟结果：

```text
M1 to M3 Docker smoke test passed.
projectId=37 requirementId=59 summaryId=27 materialId=16
intentId=13 planId=4 versionId=3 sessionId=project-37-clarification
```

冒烟使用动态 Markdown 文件，验证唯一正文进入解析摘要和知识检索；随后完成教学意图确认、方案编辑确认、三类成果生成、结构数量校验、同方案重复生成 ID 不变和刷新恢复。

Windows PowerShell 5 曾错误解码未声明 charset 的 JSON 响应，导致中文大纲在“读取后回写”时乱码。脚本已改为从原始响应流按 UTF-8 解码。最终复验还发现，无 BOM UTF-8 脚本中的中文断言常量会被 Windows PowerShell 5 按系统代码页读取；断言期望值现由 Unicode 码点构造，避免脚本源码编码掩盖接口结果。项目 37 的中文计划读取、回写和持久化断言通过。

最终 Docker 验收补充结果：`docker compose config --quiet`、`docker compose up -d --build` 和冒烟脚本均通过；backend/frontend 容器保持运行，最近 200 行日志未匹配到 `ERROR`、`Exception`、`FATAL` 或 `failed`。

## 8. 真实浏览器验收

测试地址：`http://localhost:8081/projects/35/plan` 与 `/preview`。

- 桌面视口：1600 x 1000。
- 移动视口：390 x 844。
- PPT、教案、互动题及正确答案反馈均真实点击验证。
- 移动端 `documentElement.scrollWidth === clientWidth === 390`。
- 浏览器 Console：0 errors，0 warnings。
- 页面无空白画布、无控件重叠、无正文乱码、无页面级横向溢出。

证据目录：`docs/testing/screenshots/m3-content-generation/`

```text
01-generation-plan-desktop.png
02-ppt-preview-desktop.png
03-lesson-plan-desktop.png
04-interaction-feedback-desktop.png
05-generation-plan-mobile.png
06-ppt-preview-mobile.png
```

## 9. 安全与诚实边界

- 未提交 `.env`、Dify Key、数据库文件、上传资料、宿主机绝对文件路径或用户隐私数据。
- 前端不直接调用 Dify，业务页面只调用 Spring Boot API。
- Provider 清晰显示为 Mock AI，不把确定性模板输出描述为真实大模型结果。
- 解析失败不返回依赖异常堆栈；文件路径和 Provider 密钥不进入 API 响应。

## 10. 遗留事项

1. M4：自然语言修改、版本列表/恢复/最终确认。
2. M4：使用 Apache POI 输出真实 PPTX、DOCX，并导出互动 HTML 与 ZIP。
3. Dify：实现并验证真实 Workflow Provider、超时处理和可配置 Mock 回退。
4. 产品基础：评估最小登录/注册与项目用户归属，不扩展复杂 RBAC 或多租户审批。
5. 性能：主包仍有 Vite 大 chunk 警告，后续可按依赖边界拆分 vendor chunk。

## 11. 阶段结论

M3 功能实现、完整测试、Docker 冒烟和桌面/移动端浏览器验收均已通过。实现提交为 `6aacdc7 feat: complete M3 content generation workflow`，审查入口为 [PR #10](https://github.com/AUVdidao/A12-multimodal-ai-teaching-agent/pull/10)，合并提交为 `24e463d`。

合并后在最新 `main` 重新执行后端全量测试、前端生产构建和 Docker M1-M3 冒烟：后端 `136` 个测试全部通过，前端构建通过，动态项目 `38` 完成需求、资料、知识、教学意图、方案和三类成果闭环。容器继续运行。因此 M3 可正式标记为 **main 已完成并复验通过**。
