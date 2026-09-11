# Phase F 受控部署与真实 Generation 验收报告

日期：2026-09-11
目标：`lessonforge-bridge` 当前业务库、Go Server 和共享卷
最终状态：`BLOCKED AT F3`，不得报告 `READY`

## 结论

F1 基线/备份和 F2 migration 013/Server 部署均已验证。F3 只读筛选没有找到同时满足“已有 LockedSpecification、当前 Go 模板绑定有效、所有必要材料 READY、物理文件可用”的现有 Mission，因此按任务边界停止 F4；没有通过手工 SQL 伪造业务闭环。

唯一阻塞项：当前唯一带 LockedSpecification 且全部已绑定文件 `READY` 的候选是 Mission `7`，但其最新规格 `2649fd5c-3f76-49ff-b319-28da1e6a66e3` / v`14` 使用旧模板绑定格式，缺少当前入口要求的 `bindingKind`，其 `contractVersion` 为 `1.0.0`；对应模板 `file_object_id=2` 的 MIME 是 `application/octet-stream`，而当前 Go `templatebinding.Build` 要求 PPTX MIME。必须通过受控的真实文件/元数据修复或重新锁定合规规格后，才能继续 F4。

## F1：基线、备份和活动任务核对

状态：`VERIFIED`

- 已读取任务书、Phase E 报告和 `D:\pri_work\LessonForge-go-backend\docker-compose.yml`。
- 业务库备份：`D:\pri_work\LessonForge-backups\lessonforge-live-f1-20260911T133638Z.dump`。
- 备份大小：`1,365,255` bytes；SHA-256：`4BE473379E30FA99F4E5A0A1C6721B29924E7940F67737994BC82CB80860708E`。
- PostgreSQL custom/PGDMP；`pg_restore --list` 可读，207 entries；既有备份未删除。
- 备份后变更前复核：schema 最新为 `012_conversation_summaries.sql`；活跃 `QUEUED/RUNNING/VERIFYING` Job 为 0；关键计数为 model_connections=10、locked_specifications=27、generation_jobs=27、artifacts=7、file_objects=11。

## F2：migration 013 和 Server 部署

状态：`VERIFIED`

- 已在明确的 `lessonforge` 数据库上使用源码 `D:\pri_work\LessonForge-go-backend\migrations\013_generation_job_retries.sql` 内容，通过 Postgres 容器单事务前向应用并记录 `schema_migrations`；未 reset、truncate、delete 或修改历史行。
- 应用后核验：最新 migration 为 `013_generation_job_retries.sql`；旧 `generation_jobs_specification_uq` 不存在；`generation_jobs_active_mission_uq` 和 `generation_jobs_mission_idx` 存在；关键表计数仍为 10/27/27/7/11；活跃 Job 为 0。
- 最终镜像：`lessonforge-remediation:20260911-generation-entry-final`，实际 digest/image id：`sha256:044be410837c2dadbebe145de188a5d13d0edc1f546d93ab4427dd16c375659e`。
- 新 Server：`lessonforge-bridge-server-1` running，端口 8090，挂载 `lessonforge-engine-shared:/app/data/files`，网络为 `lessonforge-bridge_default` 和 `lessonforge-a12-net`，`LESSONFORGE_ENABLE_GENERATION_WORKER=false`。
- 旧 Server 未删除，已保留为 stopped 回退容器 `lessonforge-bridge-server-1-pre-f2-20260911T134152Z`，旧 image id 为 `sha256:e0a2ac59fe10c4b5de3083dc227362e210c8ee9d324d1c2973ef3f60cbec0dc1`；旧镜像回退 tag 为 `lessonforge-bridge-server:pre-f2-20260911T134152Z`。Postgres、PPT Engine、Backend API、Parser、Generator 未重启。
- 新 Server `/healthz` HTTP 200；从新 Server 容器访问 `ppt-engine-service:8080/internal/health`、`backend-api:8080/api/health`、`file-parser-service:8080/internal/health`、`file-generator-service:8080/internal/health` 均 exit=0/UP。
- 无写入旧功能回归：未认证 `/api/auth/me`=401，随机无效登录=401；Mission/Job/Artifact/Planning 路由均命中认证保护=401，并以 `internal/platform/httpapi/server.go:85-106,615,907,931,940,1006` 做代码级核对。没有新增业务用户、会话或任务。
- Compose 直接展开仍因宿主缺少必需 `LESSONFORGE_ENCRYPTION_KEY` 插值失败；没有输出或改变该 secret，没有写 `.env`。部署通过当前容器非公开环境在进程作用域保留 secret，并只创建/启动 server。

## F3：测试 Mission 选择

状态：`BLOCKED`

候选筛选命令为只读 PostgreSQL 查询，条件包含 Mission 有 LockedSpecification、无非 READY MissionFile、存在 READY TEMPLATE。结果只有 Mission `7`：

- Mission：`7`，owner：`2`，标题为“真实 PPT 生成桥接验证”。
- 最新 LockedSpecification：`2649fd5c-3f76-49ff-b319-28da1e6a66e3`，version `14`，历史 Job `afd3e95e-b814-4f91-8a95-3226c5030da0` 为 SUCCEEDED。
- MissionFile：`2`，role=`TEMPLATE`，provenance=`TEACHER`，parse_status=`READY`；FileObject `2`，原名 `zju.pptx`，size `147487399` bytes，数据库 SHA-256 `ebac62e05725816b3dcf9f22867ef98cdedf8f14a168664cf0607b4f275a6125`，storage key 为 `uploads/1788794530525300886-zju.pptx`。
- 共享卷物理核验：`/app/data/files/uploads/1788794530525300886-zju.pptx` 存在，size `147487399` bytes，SHA-256 为 `ebac62e05725816b3dcf9f22867ef98cdedf8f14a168664cf0607b4f275a6125`，物理身份一致。
- 阻塞字段：LockedSpecification v14 的 `bindingKind` 缺失，`contractVersion=1.0.0`；绑定模板 storage key/hash/size 指向该文件，但 FileObject MIME=`application/octet-stream`。当前入口的 `store.go:2350-2394` 会拒绝该绑定，已有模板真实文件不能绕过这一校验。
- Mission `1` 虽有合规 MIME 的 READY 模板，但没有 LockedSpecification；Mission `8` 有 FAILED 材料且没有合规模板。因此没有第二个可选 Mission。

## F3.1：正常业务修复/锁定路径核验（只读）

状态：`可执行路径已确认，但需要 Mission owner 的有效认证；本阶段未执行写入`

### A. 上传/重新登记 PPTX 的正常路径

- 当前 Go Router 只提供 `POST /api/uploads` 和 `POST /api/missions/{id}/files` 两个 multipart 上传入口（`internal/platform/httpapi/server.go:84-92,463-489,795-825`），均位于 `requireAuth + requireTeacher` 保护组内；没有编辑既有 `file_objects.mime_type` 或替换既有 FileObject 的正常 UPDATE 接口。
- 两个入口都会经过 `storage.SaveMultipart`（`internal/platform/storage/storage.go:49-110`）。当浏览器把 `.pptx` 声明为 `application/octet-stream` 时，`normalizedMultipartMime` 按扩展名归一为标准 PPTX MIME；随后写入共享卷并由 Store 创建新的 FileObject/Upload 或 MissionFile。对 Mission `7` 的合规修复应是由 owner 通过 `POST /api/missions/7/files` 重新上传真实 `.pptx`，等待 Parser Worker 将新 MissionFile 从 `PENDING` 推进到 `READY`，再重新产生/批准草稿；不能直接改写 FileObject 元数据。
- Parser Worker 在 Server 启动时始终启动（`cmd/lessonforge-server/main.go:91-110`），实际解析仍依赖已配置的 Parser/RAG 外部契约；本次没有调用上传接口，也没有产生新的业务文件。

### B. Mission 1 是否能正常补齐 LockedSpecification

- 只读数据库证据：Mission `1` 属于 active teacher `owner_user_id=2`，有一个物理存在且 SHA-256 一致的 `READY/TEMPLATE` PPTX（MissionFile `1` / FileObject `1`，标准 MIME，`147487399` bytes），并有草稿 `9a25ae89-972e-4433-abf5-cd338bdbd1ac`、version `1`；草稿 JSON 为一个带非空 title 的 `slides` 数组，符合当前语义 Compiler 的最小结构要求，且没有需要额外材料授权的 `sourceRefs`。Mission `1` 当前没有 LockedSpecification。
- 正常批准接口是 `POST /api/planning/{draftId}/approve`（`server.go:99,931-938`）。`Store.ApproveDraft`（`store.go:2208-2270`）按 owner 锁定草稿，重新从当前 READY TEMPLATE 构造 Go-owned `LESSONFORGE_UPSTREAM_TEMPLATE_BINDING`，校验真实文件，再编译并只写入不可变 LockedSpecification 和 `PLAN_APPROVED` activity；批准本身不会创建 GenerationJob。
- 因此，基于当前源码和只读数据，Mission `1` 可以作为正常批准候选：由 user `2` 以 owner 身份调用 `POST /api/planning/9a25ae89-972e-4433-abf5-cd338bdbd1ac/approve`，成功后再重新读取新 LockedSpecification。该 HTTP 写入未执行，所以“实际批准成功”仍属于 `NOT_VERIFIED`，不能据此提前进入 F4。
- Mission `1` 的关联 AgentRun 当前为 `WAITING_INPUTS`；批准代码没有把该状态作为接口前置条件，但这属于业务来源审计点，主任务在实际批准前应确认该草稿确实来自可接受的 Agent 输出，而不是以状态推断成功。

### C. 认证和可复用账号边界

- `POST /api/auth/login` 接受 email/password，成功后签发 `lessonforge_session` HttpOnly cookie（也返回 token，不能写入报告或日志）；后续请求可使用 cookie 或 Bearer。前端 Go client 使用 `withCredentials: true`，Go 模式不把 token 放入 localStorage（`frontend/src/api/go.ts:253-284`、`frontend/src/api/auth.ts:28-62`）。
- 当前 user `2` 是 active `TEACHER`，正是 Mission `1/7` 的 owner；数据库中存在 active session 行，但只保存 token hash，本次没有读取或提取任何 session token。数据库中 21 个 `@example.test` 账号里有 17 个使用测试夹具的字面量密码 hash；user `2` 不是该夹具 hash，且本次没有获得其明文密码，因此不能把这些账号当作可安全复用的登录凭据。
- `/api/auth/register` 虽是公开接口，但注册的是新 owner，不能替代 user `2` 对 Mission `1/7` 的所有权认证；未经额外授权，不通过注册新账号或直接数据库写入绕过 owner 边界。

### F3.1 结论

已确认的最短合规路径是：获得 user `2` 的有效登录凭据/现有前端会话 → 对 Mission `1` 的现有草稿执行真实 `approve` → 只读复核新 LockedSpecification 的绑定与物理文件 → 再由主任务按 F4 仅创建一个 GenerationJob。当前只完成了路径核验，未执行认证写入；F3 运行状态保持 `BLOCKED`，F4/F5 仍为 `NOT_VERIFIED`。

## F3.2：专用测试账号

状态：`VERIFIED（账号/会话链路）；不构成 Mission 前置条件`

- 按主任务授权，在当前新版 Go Server 上通过正式 `POST /api/auth/register` 创建唯一 TEACHER 测试账户；密码由单次 PowerShell 进程内的随机值生成，仅用于紧接着的登录，未输出到终端、报告、日志或文件，进程结束后未保留。脱敏标识为 `lf-f3-4fe7***@example.test`。
- 真实 HTTP 结果：注册 `201`，紧接着登录 `200`，使用登录返回的 HttpOnly session cookie 调用 `GET /api/auth/me` 为 `200`；三次身份均为 userId `60`。登录响应中的 token 未输出或持久化。
- 以该会话调用只读 `GET /api/missions` 和 `GET /api/model-connections`，未发现可直接使用的 Mission 或 Model Connection；数据库只读复核 user `60` 的 missions=0、model_connections=0、active_sessions=1。该新账户不能越过 owner 隔离读取或操作既有 Mission `1/7`。
- 该账号若要成为独立测试闭环，仍需通过正常业务流程创建/取得自己的 Mission、真实上传并解析到 `READY` 的模板/材料、配置并验证自己的 Model Connection、产生真实 AgentRun/Planning Draft，再由该账号批准 LockedSpecification。按当前 F3.2 授权范围未创建这些业务数据，也未调用 approve、GenerationJob 或 Worker。

F3.2 仅证明正式注册和会话链路可用；原 F3 阻塞仍保持，F4/F5 仍为 `NOT_VERIFIED`，不得报告 `READY`。

## F3.3：隔离测试链路尝试

状态：`BLOCKED BEFORE CONNECTION`

- 本轮已核对：user `60` 仍为 active `TEACHER`，但上轮随机密码和 session token 按约定没有输出或持久化；数据库只有不可用于反向登录的 token hash。当前认证实现只有 register/login/logout，没有密码重置或按 owner 重发 session 的正常接口，因此不能猜测或反推 user `60` 的登录凭据。
- user `60` 当前没有自己的 Model Connection。现有 `VERIFIED` 连接中，外部 `api.deepseek.com` 连接属于其他 owner（user `58`），其密钥只以服务端密文保存，不能读取或复制到 user `60`；其余 `127.0.0.1:1` 连接是其他 owner 的集成夹具，不能跨 owner 复用。
- 临时本地 OpenAI-compatible mock 也不能通过当前正式 Connection 接口建立：`POST /api/model-connections` 会调用 `model.Client.ValidateBaseURL`，只接受 HTTPS，并在 DNS 与实际拨号两层拒绝 loopback、私网、链路本地和未指定地址（`internal/model/client.go:294-366`）。本次没有通过修改安全校验、代理劫持或数据库状态来绕过该边界。
- 因此 F3.3 未调用 `POST /api/model-connections`、`/api/uploads`、`/api/missions` 或 `/api/planning/{draftId}/approve`，没有创建 Connection、Mission、Upload、Planning Draft 或 LockedSpecification；也没有 GenerationJob、Worker 或 Engine 操作。只读复核仍为 user `60` missions=0/connections=0、总 GenerationJob=27、active GenerationJob=0、Worker=false。
- 具体缺口是：user `60` 的有效会话，以及一个可由该 owner 合法创建并完成 Verify 的 OpenAI-compatible Planning 来源（自己的非生产测试 key，或已由部署方批准且符合 HTTPS/SSRF 契约的外部测试服务）。在这两项具备前，无法通过正式业务接口得到真实 AgentRun/Planning Draft；禁止伪造 Draft 或直接 INSERT LockedSpecification。

F3.3 结论：当前隔离真实 Planning 链路在 Model Connection 前置处阻断，F3/F4/F5 继续保持 `BLOCKED/NOT_VERIFIED`，不得报告 `READY`；等待主任务独立复核或提供合法外部 Planning 来源后再继续。

## F3.4：admin 隔离 Mission 的真实上传/Planning 跟踪

状态：`BLOCKED AT PARSER`

- 未重复创建账号或 Connection。主任务使用已验证的 admin/user `58` 和 Connection `28`（`OPENAI_COMPATIBLE`、host=`api.deepseek.com`、model=`deepseek-v4-flash`、`VERIFIED/enabled`）通过正式接口完成真实模板上传和新 Mission 创建：UploadId=`5720b63e-22d9-4d19-abad-f66cbe862c7e`，MissionId=`67`，MessageId=`95`，AgentRunId=`f8365e6e-a6ca-4cdc-bffc-2d88907d3535`，初始 AgentStatus=`WAITING_INPUTS`。凭据、session token 和 DeepSeek key 未输出或落盘。
- 上传返回的真实 FileObject/MissionFile 身份为 MissionFile `12` / FileObject `26`，原名 `admin-generation-template-f3-20260911.pptx`，标准 PPTX MIME，size=`147487399`，SHA-256=`ebac62e05725816b3dcf9f22867ef98cdedf8f14a168664cf0607b4f275a6125`；Upload 已由正常建 Mission 流程绑定。该文件内容来自共享卷中已核验的 PPTX 副本，原文件未修改。
- 只读跟踪发现 Parser Worker 已真实领取该 PENDING 文件并将 MissionFile `12` 推进为 `FAILED`。新 Server 日志的原始业务错误为 `RAG_HTTP_401`；`mission_file_parse_results` 没有成功结果记录。当前 Mission `67` 的 AgentRun 仍为 `WAITING_INPUTS`，Planning Draft 数量为 `0`，GenerationJob 数量为 `0`，active GenerationJob 为 `0`。
- 本阶段没有调用 approve，没有手工 INSERT/UPDATE Draft 或 LockedSpecification，没有重试失败 Parser，没有启动 Generation Worker，也没有调用 PPT Engine；运行配置仍为 `LESSONFORGE_ENABLE_GENERATION_WORKER=false`。

F3.4 结论：真实上传和 Mission 建立已通过正式业务链路，但 Planning 在 Parser/RAG 认证前置处以 `RAG_HTTP_401` 阻断，尚未产生 Draft，不能进入 approve 或 F4。需要主任务先独立处理/确认当前 Parser/RAG 服务认证配置后，再从 Mission `67` 的合法业务状态继续；在此之前 F4/F5 为 `NOT_VERIFIED`，不得报告 `READY`。

## F3.5：受控注入内部服务认证并保留回退

状态：`VERIFIED（部署配置）；干净测试 Mission 待主任务认证操作`

- 按主任务授权，仅从运行中的 `a12-bridge-backend-api-1` 容器环境读取 `A12_INTERNAL_SERVICE_TOKEN`，在单次进程作用域内用于新 Server 的服务间认证；secret 未输出、未写入报告/文件/日志，也未改变持久化加密 key。
- 依据 Go 代码契约，`LESSONFORGE_RAG_BEARER_TOKEN` 由 `rag.Client.SetServiceBearerToken` 注入 Java RAG 请求的 `Authorization: Bearer`；`LESSONFORGE_TEMPLATE_CAPABILITY_BEARER_TOKEN` 是内部模板能力客户端的显式 bearer 配置。二者均只注入 Server 运行环境，不暴露给教师 REST API。
- 旧运行中的 Server 未删除，已停止并保留为 `lessonforge-bridge-server-1-pre-f35-20260911T`；F2 回退容器 `lessonforge-bridge-server-1-pre-f2-20260911T134152Z` 仍保留。新 `lessonforge-bridge-server-1` 使用最终镜像 `sha256:044be410837c2dadbebe145de188a5d13d0edc1f546d93ab4427dd16c375659e`，同一共享卷 `lessonforge-engine-shared:/app/data/files`、8090 端口和两张既有网络；Postgres 与外部服务未重启。
- 新 Server 的 `/healthz` 为 HTTP `200`，未认证 `/api/missions` 仍为 `401`，启动日志已检查；`LESSONFORGE_ENABLE_GENERATION_WORKER=false` 保持不变。未改写 MissionFile `12` 的 `FAILED` 状态，未重试 Mission `67`，未批准 Draft、未创建 GenerationJob、未调用 PPT Engine。
- 配置修复后尚未在本阶段重新创建 Mission；下一步由主任务使用已确认的 admin/user `58` 正常登录，上传同一份已核验 PPTX 并只创建一个新的干净测试 Mission。Mission `67` 不再追加文件，避免把已有 Parser 失败状态混入新验收。

F3.5 结论：服务间认证配置已经通过容器替换和健康/安全边界核验；Worker 仍关闭。待主任务创建唯一干净 Mission 后，只读跟踪 Parser → Agent/DeepSeek → Planning Draft；Draft 出现即停止，不批准、不启用 Worker，F4/F5 仍为 `NOT_VERIFIED`，不得报告 `READY`。

## F4：受控真实 Generation

状态：`NOT_VERIFIED`（按 F3 阻塞停止）

- 没有调用 `POST /api/missions/7/generation-jobs`。
- 没有启用 Worker，没有创建业务 GenerationJob，没有调用 Worker Claim、Engine compose-plan/execute、共享卷写入或 Artifact READY。

## F5：PowerPoint 验收与最终 READY

状态：`NOT_VERIFIED`

- 没有生成新的 PPTX，因此没有可交给主任务执行 Open → Save → Close → Reopen 的新文件。
- PowerPoint 验收未完成，当前不得报告 `READY`。

## 回退和未执行项

- 回退依据：停止新 `lessonforge-bridge-server-1`，恢复旧容器 `lessonforge-bridge-server-1-pre-f2-20260911T134152Z` 的原名并启动；旧容器/镜像/volume 均保留。
- 未删除历史 Artifact，未删除业务数据库行，未重启外部服务。
- 下一步唯一必要前置：通过正常上传/文件元数据修复流程补齐合规 PPTX MIME，并重新产生符合当前 Go `LESSONFORGE_UPSTREAM_TEMPLATE_BINDING` / `contractVersion` 的 LockedSpecification；完成 F3 复核后，才可按 F4 只对一个 Mission 启用 Worker。
