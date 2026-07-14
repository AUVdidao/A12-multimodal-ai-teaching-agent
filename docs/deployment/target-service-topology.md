# A12 Target Service and Container Topology

## 1. Decision Source

The formal overview and deployment documents define a target cloud topology with `frontend-web`, `backend-api`, `file-parser-service`, `file-generator-service`, `reverse-proxy`, and `monitor-log`, plus managed or independently deployed database, object-storage, vector-search, and Dify capabilities.

The current two-container Docker baseline is a runnable local slice, not the final topology. The migration rule is: a service becomes a separate container only when it owns a real runtime responsibility, API contract, health check, failure behavior, and integration test. Empty placeholder containers are prohibited.

## 2. Target Runtime Allocation

| Container/service | Responsibility | Network exposure | Delivery phase |
| --- | --- | --- | --- |
| `reverse-proxy` | Single browser entry, SPA/static routing, `/api` proxy, security headers, later HTTPS | Public | C6 |
| `frontend-web` | Built Vue assets only; no secrets and no business persistence | Internal behind proxy | Existing, renamed/split in C6 |
| `backend-api` | Authentication, RBAC, projects, collaboration workflow, persistence, orchestration, stable public APIs | Internal behind proxy | Existing, renamed in C6 |
| `file-parser-service` | Stateless PDF/DOCX/PPTX/TXT/MD extraction behind an internal API | Internal only | C6 |
| `file-generator-service` | Stateless PPTX/DOCX/package generation from validated structured payloads | Internal only | C6 |
| `object-storage` | Uploaded source files and generated exports; MinIO locally, cloud object storage later | Internal/admin only | C6 |
| `database` | Relational business, identity, approval, publication, and Q&A data; PostgreSQL target | Internal only | C6 migration after P0 entities stabilize |
| `dify-workflow` | External Dify Cloud by default; optional self-host profile, never called by the browser | Outbound backend dependency | Existing adapter; real credentials later |
| `vector-search` | Real embeddings/vector retrieval; local deterministic search remains clearly labelled until enabled | Internal/managed | P1, not falsely enabled in P0 |
| `monitor-log` | Aggregated logs and health dashboards; container logs remain the C0-C5 evidence source | Internal/admin only | C6 optional profile |
| `redis` | Async job state and transient coordination if required by parser/generator workloads | Internal only | P1, not required for synchronous P0 |

## 3. Request Paths

```text
Browser
  -> reverse-proxy
     -> frontend-web (static application)
     -> backend-api (/api)
        -> database
        -> object-storage
        -> file-parser-service (internal parsing API)
        -> file-generator-service (internal generation API)
        -> Dify Cloud (server-side HTTPS, mock fallback)
        -> vector-search (only when the real provider is enabled)
```

The browser never receives database, object-storage, Dify, or internal-service credentials. Parser and generator endpoints are not published on host ports in the final Compose topology.

## 4. Service Contracts Planned for C6

### Parser

```http
POST /internal/file-parser/tasks
GET  /internal/file-parser/tasks/{taskId}
GET  /internal/health
```

Input identifies an object key and media type. Output includes extraction status, normalized text, summary metadata, and a stable error code.

### Generator

```http
POST /internal/file-generator/pptx
POST /internal/file-generator/docx
POST /internal/file-generator/package
GET  /internal/health
```

Input is backend-validated structured content. Output is an object key and content metadata, not a public, permanent storage URL.

## 5. Migration Sequence

1. Finish P0 collaboration entities and public backend contracts in `backend-api`.
2. Replace invalid/placeholder frontend views with those real contracts.
3. Move existing parsing code behind a parser adapter, then extract the adapter implementation to `file-parser-service` without changing public material APIs.
4. Move existing PPTX/DOCX generation behind a generator adapter, then extract it to `file-generator-service` without changing public export APIs.
5. Introduce local object storage and migrate file references from container paths to object keys.
6. Add a separate `reverse-proxy`, keep `frontend-web` private, and expose one browser port.
7. Migrate H2 to PostgreSQL only after schema and migration scripts cover the complete P0 model.
8. Add optional monitoring/vector/Redis profiles only when their real workflows are testable.

## 6. Acceptance Gate

Each extracted service must pass:

- image build and container health check;
- backend-to-service network test with no public host port;
- timeout and unavailable-service behavior;
- no secret in frontend assets, image layers, Compose output, or logs;
- existing public API regression tests;
- full Docker browser workflow and persisted restart test;
- formal evidence in `D:\服务外包正式文档\日志`.
