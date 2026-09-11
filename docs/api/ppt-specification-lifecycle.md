# PPT Specification 生命周期 API

阶段3只在 Spring Boot 主系统中管理逐页结构化 Specification，不生成 PPTX、不调用模型、不接入 Executor。

## 状态规则

```text
DRAFT --submit-review--> REVIEW --lock--> LOCKED
                         REVIEW --return-to-draft--> 新 DRAFT
```

- DRAFT 可由项目教师编辑；每次编辑必须提交 `expectedChecksum`，并可提交 `expectedEntityVersion` 做乐观并发校验。
- REVIEW 内容冻结；不能通过编辑接口覆盖。
- LOCKED 内容和 checksum 永久只读；当前 API 没有删除接口。
- 退回保留旧 REVIEW，创建更高版本号的新 DRAFT。
- checksum 是结构化内容快照的 SHA-256：包含模板绑定、页数约束和逐页内容/素材/来源，排除状态与审计时间，因此 REVIEW→LOCKED 不改变内容 checksum。

## API

基路径：`/api/v1/projects/{projectId}/ppt-specifications`

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/` | 读取项目全部历史版本与 latest |
| GET | `/latest` | 读取最新版本 |
| POST | `/` | 创建项目首个 DRAFT |
| POST | `/proposals` | Planning proposal 边界；只创建版本，不更新现有 DRAFT |
| GET | `/{versionId}` | 读取指定版本 |
| PUT | `/{versionId}` | 仅更新 DRAFT |
| POST | `/{versionId}/submit-review` | DRAFT 冻结为 REVIEW，并记录提交 checksum/审计字段 |
| POST | `/{versionId}/return-to-draft` | REVIEW 退回并复制为新 DRAFT |
| POST | `/{versionId}/lock` | 再校验 checksum 后批准为 LOCKED |

写入 payload 保持 `templateProfileId`、`templateProfileVersion`、`templateCapabilityViewVersion`、`templateCapabilityViewChecksum`、目标页数、语义布局、内容块、素材需求和 provenance 等结构化字段；不接受把整份 Specification 填成自然语言字段。

`/proposals` 的 `INITIAL_PROPOSAL` 用于首次生成；后续 `PATCH` / `NEW_DRAFT_VERSION` 必须带最新 `baseVersion` 与 `baseChecksum`。若教师已经编辑当前 DRAFT，服务端返回 409，Planning Agent 不能后台覆盖教师草稿。

## 模板 Source 详情读取

模板详情中的 `sourceVersions[]` 是摘要集合：`processingRuns`、`structuralSnapshot` 和 `renderedSlideSet` 不在摘要读取中展开，且 `detailsLoaded=false`。前端需要按需调用 `GET /api/projects/{projectId}/templates/{templateId}/source-versions/{sourceVersionId}`；该接口返回 `detailsLoaded=true`，并在返回结构快照前统一重新计算并核对快照 SHA-256。发现缺失、格式损坏或 checksum 不一致时 fail closed，返回冲突错误，不返回未校验的结构内容。

