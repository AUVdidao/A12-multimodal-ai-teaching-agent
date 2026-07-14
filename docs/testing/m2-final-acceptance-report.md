# A12 M2 资料上传、知识库检索与教学意图确认最终验收报告

## 1. 任务范围

本报告覆盖 TA-011 至 TA-015：资料上传与安全存储、资料用途绑定、原型解析、知识片段索引与可解释检索、资料增强教学意图生成/编辑/确认，以及 Docker、浏览器和合并后主线验收。

- 基线提交：`513377a39349a450f06d654174ffb550fb4065b9`
- 开发分支：`m2-material-knowledge-complete`
- 运行模式：本地 Mock AI + H2 文件数据库
- 真实 Dify、真实 OCR、真实向量数据库不在本次 M2 范围内

最终收尾记录：

- 分支提交：`e5c22404eae77e2e20b55eb7578272ab7d6c93ce`
- PR：[#4 feat: complete M2 material and knowledge workflow](https://github.com/AUVdidao/A12-multimodal-ai-teaching-agent/pull/4)
- PR base/head：`main` <- `m2-material-knowledge-complete`
- 合并提交：`11bae1428c51614befcba31f1ae47a837d84553b`
- 合并后 main：`11bae1428c51614befcba31f1ae47a837d84553b`

## 2. TA-011 资料上传与存储

结果：通过。

- 支持类型：`PDF`、`DOCX`、`PPTX`、`PNG`、`JPG`、`JPEG`
- 单文件大小：20 MB；请求体上限 21 MB
- 存储目录：开发环境 `./data/uploads`，Docker 环境 `/app/data/uploads`
- Docker 持久化：`backend-data` named volume 挂载到 `/app/data`
- 文件名：服务端使用 UUID 文件名，按 `projectId` 分目录存储，上传使用临时 `.part` 文件并原子移动
- 下载：只通过资料 ID 查询当前项目资料，再由存储服务解析受控路径返回，不接受任意路径
- 安全措施：扩展名和 MIME 双校验、空文件拒绝、大小限制、路径穿越防护、项目级隔离、受控下载响应头

接口：

```text
POST /api/projects/{projectId}/materials
GET  /api/projects/{projectId}/materials
GET  /api/projects/{projectId}/materials/{materialId}
GET  /api/projects/{projectId}/materials/{materialId}/download
```

## 3. TA-012 资料用途绑定

结果：通过。

- 用途类型：教材依据、案例素材、习题来源、知识补充，并保留图片素材、视频内容等扩展类型
- 支持同一资料绑定多个用途和用途说明
- 保存后重新查询可回显，重复提交按资料和用途去重
- 所有绑定查询均校验资料属于当前项目，跨项目资料不能读取或修改

接口：

```text
PUT /api/projects/{projectId}/materials/{materialId}/usages
GET /api/projects/{projectId}/materials/{materialId}/usages
```

## 4. TA-013 原型解析

结果：通过。

- 状态流：`PENDING` -> `PROCESSING` -> `SUCCEEDED`，失败进入 `FAILED` 并保留失败原因
- 解析内容：基于文件名、资料说明、已确认用途和教学需求生成确定性的原型摘要、关键词和适用教学环节
- 结果字段：摘要、关键词、教学环节、解析状态、解析时间、失败原因
- 失败重试：`POST /api/projects/{projectId}/materials/{materialId}/parse/retry`
- 索引：成功解析后自动创建知识片段；重复解析先清理当前资料旧片段，再创建新索引，避免重复索引
- Mock 边界：不读取文件全文，不宣称 OCR、视频理解或真实多模态解析

接口：

```text
POST /api/projects/{projectId}/materials/{materialId}/parse
GET  /api/projects/{projectId}/materials/{materialId}/parse-result
POST /api/projects/{projectId}/materials/{materialId}/parse/retry
POST /api/projects/{projectId}/materials/{materialId}/index
```

### 4.1 2026-07-14 正文提取加固

M3 开发前完成了一次向后兼容的 M2 加固，替代了“只根据文件名和元数据构造摘要”的旧原型解析器：

- TXT、MD：严格按 UTF-8 读取，支持 BOM，非法编码返回受控失败。
- PDF：使用 PDFBox 读取页面文本；加密、损坏、扫描件无正文时不伪造文本。
- DOCX：使用 Apache POI XWPF 读取段落和表格。
- PPTX：使用 Apache POI XSLF 读取幻灯片、表格和组合图形中的文本。
- 图片：明确返回“未启用 OCR”，不生成推断正文。
- 视频：明确返回“未启用转写”，不生成推断正文。
- 安全限制：解析输入上限 20 MB、提取文本上限 200,000 字符、PDF 页数和 PPTX 页数上限、OOXML 条目数/单条目/总解压大小限制及路径穿越检查。
- 解析摘要、关键词和后续知识片段均来自实际提取正文；课程主题只作为单独标明的上下文，不冒充文件内容。

加固测试使用程序化生成的 TXT、MD、PDF、DOCX、PPTX、损坏文件、空文件、图片和视频样本，后端完整测试增加到 136 项并全部通过。Docker M1 至 M3 冒烟使用动态 Markdown 文件验证唯一正文可出现在解析摘要和知识检索结果中。

## 5. TA-014 知识片段与检索

结果：通过。

- 每份成功解析资料生成 3 个结构化知识片段
- 片段包含项目、资料、片段序号、标题、内容、关键词、用途类型和来源文件
- 检索方式：本地确定性关键词检索，不是真实向量 RAG
- 评分：查询词与片段标题、内容、关键词及用途标签的命中权重累加；按分数降序、片段序号稳定排序
- 结果包含：匹配分数、命中理由 `hitReason`、来源文件、来源资料 ID、用途标签和片段内容
- 项目隔离：检索只读取当前项目已成功解析的知识片段

接口：

```text
GET  /api/projects/{projectId}/knowledge/overview
POST /api/projects/{projectId}/knowledge/search
```

页面明确标识“本地原型检索”和“当前不是向量数据库 RAG”。

## 6. TA-015 教学意图确认

结果：通过。

- 生成：融合教师已确认需求、资料用途、原型解析摘要和本地知识命中
- 证据：每个证据项包含来源资料、命中理由、用途和知识片段引用
- 编辑：教师可编辑生成目标、内容依据、教学方法、互动方式、输出类型和风格偏好
- 确认：确认后状态为 `CONFIRMED`，前端字段锁定，M3 入口保持锁定
- 刷新恢复：重新进入页面仍读取当前项目最新教学意图和确认状态

接口：

```text
POST /api/projects/{projectId}/teaching-intents/generate
GET  /api/projects/{projectId}/teaching-intents/latest
PUT  /api/projects/{projectId}/teaching-intents/{intentId}
POST /api/projects/{projectId}/teaching-intents/{intentId}/confirm
```

## 7. H2 ENUM 兼容迁移专项审查

涉及表和列：

| 表 | 列 |
| --- | --- |
| `uploaded_materials` | `file_type` |
| `material_purposes` | `purpose_type` |
| `knowledge_chunk_usages` | `usage_type` |

- 原类型：H2 对 Java enum 生成的受限字符列定义
- 新类型：`VARCHAR(32)`、`VARCHAR(48)`、`VARCHAR(48)`
- 启用条件：仅当 JDBC 元数据数据库产品名为 H2 时执行
- 幂等方式：重复启动重复执行相同 `ALTER COLUMN`，旧 Docker volume 已通过重启验证
- 旧数据保留：只放宽列类型，不删除表、不清空数据、不重建数据库
- 路径依赖：迁移通过注入 `DataSource` 判断数据库，不依赖固定文件路径
- 失败处理：记录明确错误日志并重新抛出，阻止应用以不确定 schema 启动
- Docker 旧卷验证：旧卷中已有 M1 数据；迁移后 M2 新枚举可保存，backend 重启无重复迁移错误，旧数据仍可查询

## 8. 自动化测试与构建

后端：

```text
mvn test（本机 PATH 不含 Maven，使用等价的 Maven launcher 执行）
Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试使用内存 H2、临时上传目录，不访问真实网络或真实用户文件。开发 H2 文件哈希在测试前后保持不变：`884B94DDABF9B340918699720BE8D1DE29697EE4A4EBC98B486953E43B100C86`。

前端：

```text
npm.cmd run build
通过；仅有既有 Vite/Rollup chunk size 和第三方注释警告
```

`package.json` 未配置 lint 或前端 test 脚本，因此未执行不存在的命令。`git diff --check` 通过。

## 9. Docker 验收

已执行并通过：

```text
docker compose config
docker compose build
docker compose up -d
scripts/docker-smoke-test.ps1
docker compose restart backend
docker compose logs backend --tail=300
docker compose logs frontend --tail=150
```

Smoke 动态输出包含：`projectId`、`materialId`、`intentId`、`sessionId`。M1 和 M2 均通过：项目创建、模式设置、需求/澄清/摘要确认、资料上传、用途绑定、解析、知识检索、教学意图生成/编辑/确认，以及对话历史查询。

合并后 main 的 smoke 动态输出：`projectId=27`、`materialId=6`、`intentId=4`、`sessionId=project-27-clarification`。

重启持久化通过：backend 重启后资料元数据、下载字节数、用途、解析结果、知识片段和教学意图仍可读取。日志未发现 500、502、Bean 冲突、H2 锁、迁移重复错误、路径下载或真实 Dify 请求。

最终容器按项目负责人要求保持运行：frontend `http://localhost:8081`，backend `http://localhost:8080`。

## 10. 浏览器验收

真实 Docker 浏览器链路通过：项目创建 -> M1 摘要确认 -> M2 上传 -> 用途绑定 -> 原型解析 -> 刷新恢复 -> 知识检索 -> 教学意图生成 -> 编辑 -> 确认 -> 刷新后仍为 `CONFIRMED`。

- 测试项目：动态项目 ID，浏览器验收实际使用 project 25
- 测试资料：自动生成的非敏感 1x1 PNG，文件名 `a12-browser-material.png`
- Console：无业务错误
- Network：无失败业务请求、无 404/500/502
- 响应式：`1366x768`、`1920x1080`、`1024x768`、`768x900`、`390x844` 均无横向溢出
- 页面状态：M2 五步进度、解析完成、可解释检索、教学意图草稿/确认和 M3 锁定边界清晰

## 11. 截图与文档索引

验收报告：本文件。

截图目录：`docs/testing/screenshots/m2-material-knowledge/`

```text
01-material-upload-page-1366.png
02-material-list-1366.png
03-usage-bound-1366.png
04-parse-result-1366.png
05-knowledge-results-1366.png
06-intent-draft-1366.png
07-intent-confirmed-1366.png
08-material-complete-1920.png
09-knowledge-results-1920.png
10-intent-confirmed-1920.png
```

截图来自真实 Docker 浏览器运行，不包含 token、私人账号、真实资料或宿主机路径。关键区域截图使用普通视口或元素截图，避免 sticky 导航重复覆盖。

## 12. 安全与仓库卫生

- 未提交真实密钥、`.env`、上传资料、H2 数据文件、Docker volume 内容、构建产物、日志或 IDE 文件
- `.env`、`target`、`node_modules`、`dist`、数据库和上传数据均由忽略规则排除
- 报告和代码不依赖宿主机绝对路径；本机 Maven launcher 仅作为执行环境，不写入项目配置
- 文件服务不允许任意路径下载，跨项目资料访问返回受控错误
- Mock 解析和本地检索均明确标注边界，不伪装为真实 AI 或真实向量 RAG

## 13. 已知限制与 M3 未完成能力

以下能力仍不属于 M2：图片 OCR、视频语音转写、旧版二进制 Word/PPT、XLSX 正文提取、真实向量 RAG、真实 Dify 工作流、版本导出、生产数据库、对象存储和生产部署。TXT、MD、PDF、DOCX、PPTX 正文提取已在 2026-07-14 加固中实现。

## 14. 最终验收结论

**M2 已完成、已合并、main 复验通过。**

合并后复验结果：后端 `mvn test` 为 `108/108` 通过，前端 `npm.cmd run build` 通过，Docker `config/build/up/smoke` 通过，backend 重启后资料下载仍为 68 字节、解析状态为 `SUCCEEDED`、知识片段为 3 个、教学意图为 `CONFIRMED`，`git status` clean。容器按项目负责人要求保持运行。
