# A12 Identity and RBAC API

## 1. Scope

This contract covers the C0 collaboration-platform identity foundation:

- public student registration;
- username/password login;
- opaque, revocable Bearer sessions;
- one account with multiple roles;
- active-role switching;
- logout and server-side authorization.

The business roles are `TEACHER`, `LEADER`, and `STUDENT`. The active role is part of the server-side session and is not trusted from a client-only flag.

## 2. Common Rules

- Base path: `/api/v1/auth`.
- Protected requests use `Authorization: Bearer <token>`.
- Passwords are stored as BCrypt hashes.
- The raw token is returned only when a session is created. The database stores its SHA-256 digest.
- `401` means no valid session. `403` means the session is valid but its active role cannot perform the action.
- Public registration always creates a `STUDENT`, even if an unknown `roles` field is supplied.

## 3. Endpoints

### 3.1 Register a Student

`POST /api/v1/auth/register`

```json
{
  "username": "student01",
  "displayName": "学生甲",
  "password": "Student123!"
}
```

Validation:

- `username`: 3-50 characters; letters, digits, `.`, `_`, and `-` only.
- `displayName`: required, up to 100 characters.
- `password`: 8-72 characters.

The response is an authenticated student session.

### 3.2 Login

`POST /api/v1/auth/login`

```json
{
  "username": "teacher",
  "password": "Teacher123!",
  "activeRole": "TEACHER"
}
```

`activeRole` is optional. When provided, it must be assigned to the account.

### 3.3 Read the Current Identity

`GET /api/v1/auth/me`

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 2,
    "username": "teacher",
    "displayName": "张老师",
    "roles": ["TEACHER"],
    "activeRole": "TEACHER"
  }
}
```

### 3.4 Switch Active Role

`POST /api/v1/auth/switch-role`

```json
{
  "role": "LEADER"
}
```

The same token remains valid. The server updates its active role only when the account owns that role. Switching a teacher-only account to `LEADER` returns `403`.

### 3.5 Logout

`POST /api/v1/auth/logout`

Logout revokes the current session. Reusing the token afterwards returns `401`.

## 4. Session Response

Registration and login return:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "raw-token-returned-once",
    "expiresAt": "2026-07-15T06:00:00",
    "user": {
      "id": 2,
      "username": "teacher",
      "displayName": "张老师",
      "roles": ["TEACHER"],
      "activeRole": "TEACHER"
    }
  }
}
```

## 5. Route Protection

| Route family | Access rule |
| --- | --- |
| `/api/health` | Public |
| `/api/v1/auth/register` | Public |
| `/api/v1/auth/login` | Public |
| `/api/v1/auth/me`, role switch, logout | Any authenticated role |
| Existing `/api/**` teacher production flow | Active role `TEACHER` |
| New `/api/v1/**` business APIs | Authenticated first; service-level role and data-scope checks second |

Frontend route guards and hidden controls are user-experience features only. Every protected operation is authorized again by the backend.

## 6. Demo Mode

The local Docker demo can seed four accounts when `A12_DEMO_SEED_ENABLED=true` and show quick-fill account buttons when `VITE_DEMO_MODE=true`.

| Account | Active role | Purpose |
| --- | --- | --- |
| `teacher` | `TEACHER` | Teacher production flow |
| `leader` | `LEADER` | Teaching management flow |
| `student` | `STUDENT` | Student learning flow |
| `multi` | `TEACHER` or `LEADER` | Same-token role switching |

Demo passwords are local sample values supplied through environment variables. Production deployments must disable demo seeding and demo UI, provision privileged accounts through a controlled process, and use strong secret values outside Git.

## 7. Current Security Trade-offs

- The prototype stores the Bearer token in browser local storage. This supports the current SPA and role-switching flow but requires strict XSS prevention. A production deployment should evaluate an HttpOnly, Secure, SameSite cookie.
- H2 is suitable for a local prototype only. Production identity/session data requires a managed relational database, migration scripts, backups, and key/secret rotation procedures.
- Demo account passwords are intentionally discoverable only in demo builds and must never be reused outside the local demonstration environment.
