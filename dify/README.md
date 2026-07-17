# A12 Dify 工作流资产包

本目录提供 WF-01 至 WF-07 的 Dify Workflow DSL、结构化输入输出样例和轻量静态校验脚本。它是后端 Dify 适配器的交付资产，不是前端集成包，也不是已经在目标 Dify 实例验证通过的运行记录。

> 当前状态：**待目标实例导入验证**。DSL 已按 Dify Workflow 导出文件的常见 `app / dependencies / kind / version / workflow / graph` 结构编写并通过仓库内静态校验，但本次未操作 Docker，也没有目标 Dify 实例、模型供应商凭据或发布权限，因此不能声明已经导入、运行或发布成功。

## 接入边界

```text
Browser / Vue
    -> Spring Boot API
        -> AIWorkflowGateway
            -> MockAIWorkflowGateway
            -> Dify adapter (待实现)
                -> published Dify Workflow API
```

- 前端绝不直接调用 Dify，也不接收、保存或展示 Dify API key。
- Spring Boot 是唯一调用入口，负责鉴权、业务状态、上下文组装、结构校验、持久化、审计与降级。
- Dify 只负责推理和工作流编排，不是项目、资料、教学意图或成果版本的事实数据源。
- API key 只能保存在服务端环境变量或密钥管理系统中；本目录不包含任何真实 key。
- Mock 与 Dify 必须返回等价业务结构，前端不感知当前供应商。

Dify 官方的 [Run Workflow API](https://docs.dify.ai/api-reference/workflows/run-workflow) 同样明确建议 API key 只保存在服务端。工作流搭建和节点编排可参考官方 [Workflow quick start](https://docs.dify.ai/en/guides/application-orchestrate/creating-an-application)。DSL 骨架参考 Dify 官方仓库中的 1.9.2 导出样例结构；Dify 维护者也说明 DSL 本质上随画布数据结构演进，因此目标实例版本仍必须实际导入确认：[DSL 说明讨论](https://github.com/langgenius/dify/discussions/8090)、[1.9.2 导出结构样例](https://github.com/langgenius/dify/issues/27353)。

## 资产清单

| 编号 | 文件 | 场景 | 当前 Gateway 对应方法 |
| --- | --- | --- | --- |
| WF-01 | `workflows/WF-01-requirement-clarification.yml` | 需求澄清 | `clarifyRequirement` |
| WF-02 | `workflows/WF-02-requirement-summary.yml` | 需求摘要 | `summarizeRequirement` |
| WF-03 | `workflows/WF-03-material-analysis.yml` | 资料分析 | `analyzeMaterial` |
| WF-04 | `workflows/WF-04-rag-teaching-intent.yml` | RAG + 教学意图 | `retrieveKnowledge` + `buildTeachingIntent` |
| WF-05 | `workflows/WF-05-generation-plan.yml` | 生成方案 | `createGenerationPlan` |
| WF-06 | `workflows/WF-06-structured-content-draft.yml` | 结构化内容草稿 | **当前缺失** |
| WF-07 | `workflows/WF-07-edit-intent.yml` | 修改意图 | `reviseArtifact`（语义仍需适配） |

每个工作流对应一个 `contracts/WF-XX.contract.examples.json`，其中包含：

- `request`：Gateway 应组装的逻辑输入；
- `successResponse`：可继续业务流程的统一成功包络；
- `errorResponse`：不可继续时的统一错误包络。

`contracts/common-response-envelope.schema.json` 定义所有工作流共享的响应外壳。业务 `data` 的具体形状由各 WF 样例和 DSL Prompt 共同约束。

## Dify 输入输出约定

为降低 Dify Start 节点类型差异对后端 DTO 的影响，七个工作流都使用相同的物理接口：

- Start 输入变量：`request_json`，类型为长文本，内容是对应契约文件中 `request` 对象的 JSON 序列化结果；
- End 输出变量：`result_json`，类型为字符串，内容必须是一个合法 JSON 对象；
- 模型不得输出 Markdown 正文、代码围栏或 JSON 前后的解释文字；
- 后端必须先做 JSON 语法校验，再做 Schema/字段校验、业务校验和安全校验；失败结果不得入库。

统一响应字段来自《Prompt 与结构化输出契约说明书 v2》：

| 字段 | 说明 |
| --- | --- |
| `workflowCode` | 固定为 `WF-01` 至 `WF-07` |
| `success` | 结果是否可被业务继续使用 |
| `data` | 场景业务结果；失败时为 `null` |
| `warnings` | 可继续但需要教师关注的风险信息 |
| `errors` | 不可继续的 `{code, message, field, retryable}` 对象数组 |
| `confidence` | 0 到 1；失败时可为 0 |
| `traceHint` | 后端提供的非敏感关联标识，不得包含 key 或隐私信息 |

调用发布后的工作流时，Dify HTTP 请求形状应由服务端适配器生成：

```json
{
  "inputs": {
    "request_json": "<serialized contracts/WF-XX.contract.examples.json request object>"
  },
  "response_mode": "blocking",
  "user": "<opaque backend-generated caller id>"
}
```

服务端从 Dify 响应的 `data.outputs.result_json` 取出字符串并解析。不得把 Dify 原始响应直接透传给浏览器。

## 导入、发布与验证

1. 在目标 Dify 工作空间确认版本，并安装一个组织允许使用的模型供应商插件。
2. 在 Studio 中逐个导入 `workflows/*.yml`。若实例提示 DSL 版本升级，先查看差异，再由该实例重新导出并回填兼容版本。
3. 打开每个 LLM 节点，选择目标实例中已配置的模型。资产中的 `langgenius/openai/openai` 与 `gpt-4o-mini` 只是无凭据的节点占位配置，不代表目标环境必须使用 OpenAI。
4. 将对应 `contracts/WF-XX.contract.examples.json` 的 `request` 压缩为 JSON 字符串，填入 `request_json` 做 Preview Run。
5. 验证 End 节点仅返回 `result_json`，且可解析、`workflowCode` 正确、成功和错误分支均满足契约。
6. 至少为每个 WF 验证一个成功样例、一个缺失必填字段样例和一个冲突/风险样例。
7. 通过人工审查后发布工作流，再创建应用 API key。key 只写入服务端 Secret/环境配置，不写入代码、DSL、日志或浏览器存储。
8. 实现并测试 Dify Gateway 适配器：超时可重试，解析或 Schema 失败不得持久化，按配置决定是否回退 Mock。
9. 完成后保留目标实例版本、导入结果、发布版本、脱敏调用证据和后端测试结果。本目录目前没有这些运行证据，所以状态保持“待目标实例导入验证”。

## Mock 与 Dify 切换现状

当前代码以 `a12.ai.provider` 选择 `MOCK` 或 `DIFY`，并以 `a12.ai.fallback-to-mock` 控制 Dify 不可用时是否回退。实际状态如下：

- `AIWorkflowGatewayRouter` 目前只调用 `MockAIWorkflowGateway`；选择 Dify 且禁用回退时会抛出“real Dify workflow is not implemented”异常。
- 当前 `AiWorkflowProperties.Dify` 只有一组 `baseUrl / workflowId / apiKey`，而本资产包有七个独立 Workflow 应用。真实接入前需在后端设计每个 WF 的应用标识/密钥映射，或明确采用一个总编排应用；本资产包不修改该配置。
- 切换到 Dify 前，必须让 Dify 适配器把现有 Gateway DTO 映射到这里的 `request_json`，并把 `result_json` 映射回现有 DTO。
- Mock 与 Dify 的契约一致性应由后端聚焦测试覆盖，不能仅凭 Dify Preview 截图判断。

### WF-06 明确缺口

当前 `AIWorkflowGateway` 没有“根据已确认 generation plan 生成结构化 PPT/DOCX/互动内容草稿”的方法，也没有对应请求/响应 DTO。`createGenerationPlan` 只覆盖 WF-05；`reviseArtifact` 对应修改流程，不能替代首次草稿生成。因此 WF-06 DSL 和契约可以先导入验证，但在补齐 Gateway 方法、DTO、Router/Dify adapter 与持久化调用链之前，后端无法真实调用 WF-06。

WF-07 也存在语义映射注意点：文档定义的是“理解修改意图”，当前 Mock `reviseArtifact` 会直接给出修改后内容。接入时应明确是先解释意图再执行修改，还是调整 Gateway 契约，避免把意图识别结果误当成已生成的新版本。

## 静态校验

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\dify\scripts\validate-workflows.ps1
```

校验器不引入 YAML 模块，执行以下轻量检查：

- WF-01 至 WF-07 的 DSL 和契约文件是否齐全、编号是否唯一；
- DSL 是否包含 Workflow 基本结构、Start/LLM/End 节点、`request_json` 与 `result_json`；
- Prompt 是否明确要求仅输出 JSON 且禁止 Markdown/代码围栏；
- JSON Schema/样例是否可由 PowerShell 解析；
- 成功/错误样例的编号、状态和错误数组是否符合约定；
- 资产中是否出现常见真实密钥形态。

静态校验不能替代 Dify 目标版本导入、模型节点配置、Preview Run、发布 API 调用和后端集成测试。
