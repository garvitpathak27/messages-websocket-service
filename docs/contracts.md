# Contracts — messages-websocket-service

Written in Phase 1. "Settled" means established before the build guide; Steps 2–3
verify those and pin the rest. Recorded output is pasted verbatim from the
commands in the guide — never edited by hand, never containing a token.

## Platform (settled)

- Public entry: cloud-gateway `http://localhost:8090`. No other backend port is published.
- Network: external Docker network `chatapp`. Eureka: `http://127.0.0.1:8761`.
- Services register in Eureka **by container IP**. A Compose service name there
  breaks two ways: the name vanishes from Docker DNS when the container stops
  (so a dead instance looks *unresolvable* rather than *unreachable*), and every
  replica registers the same instance id under `--scale`.
- Correlation: the gateway sends **no** correlation header — it only logs
  Netty's internal `requestId`. This service generates `X-Request-ID` itself on
  every request, matching chat-service's `CorrelationIdMiddleware` spelling.
- Verification identity: `POST http://localhost:8090/api/v1/auth/login/`
  body `{"email":"alice+e2e@example.com","password":"TestPass123!"}`,
  token field `access_token`.
- Trust boundary: membership of the `chatapp` network is the platform's only
  network-level control. This service validates every token itself and treats no
  inbound header as proof of identity.

---

## Auth service (Step 2)

### Transport

- gRPC target `auth-service:50051`, **plaintext**, over Compose DNS. Never via Eureka.
- Deliberate: auth gRPC is the one dependency that does not use discovery, so a
  Eureka outage cannot break token validation.
- Because it is reached by DNS name, `docker compose stop auth-service` produces
  `UNAVAILABLE` with `UnknownHostException` rather than a connection refusal.

### The proto — canonical location and hash

Two copies exist and **must stay identical**. auth-service owns it; chat-service
syncs. Step 19 copies it a third time for this service.

```
90b67e67709216c367987e44d5d49d0e9c55dc2cd6102568f1cee1e626e0e5fa  ./auth-service/proto/auth.proto
90b67e67709216c367987e44d5d49d0e9c55dc2cd6102568f1cee1e626e0e5fa  ./chat-service/contracts/auth.proto
```

chat-service already has drift machinery worth reusing rather than reinventing:

```
chat-service/scripts/sync-proto.sh
chat-service/scripts/generate-proto.sh
chat-service/scripts/check-proto-drift.sh
```

### Service and method, from the proto itself

```protobuf
syntax = "proto3";
package auth;

service AuthService {
  rpc ValidateToken (TokenRequest) returns (TokenValidationResponse);
  rpc GetPermissions (TokenRequest) returns (PermissionsResponse);
}

message TokenRequest {
  string access_token = 1;
}

message TokenValidationResponse {
  bool active      = 1;
  string user_id   = 2;
  repeated string roles       = 3;
  repeated string permissions = 4;
}
```

| | Value |
|---|---|
| Fully-qualified service | `auth.AuthService` |
| Method | `ValidateToken` |
| Request type | `auth.TokenRequest` |
| Request field | **`access_token`** (field 1) |
| Response type | `auth.TokenValidationResponse` |
| Validity flag | **`active`** (field 1, `bool`) |
| Identity field | `user_id` (field 2) — the only source of `senderId` (D8) |

**The names matter more than they look.** The spec and earlier drafts of this
guide said `VerifyToken` / `is_valid`. The proto says `ValidateToken` / `active`,
and the request field is `access_token`, not `token`.

A wrong message shape does **not** fail loudly. Proto3 drops field numbers it
does not recognise and leaves every expected field at its zero value, so
`bool active` decodes to `false`. Every token — including one issued a second
ago — then comes back invalid, and every request 401s. That looks exactly like
auth working correctly and strictly.

### gRPC status mapping (verified behaviour, 2026-10-26)

auth-service's handler distinguishes these deliberately. Step 20 must preserve
the distinction rather than collapsing it.

| Situation | gRPC status | This service returns |
|---|---|---|
| token valid | `OK`, `active: true` | proceed; `user_id` is the sender |
| token invalid / expired / revoked | `OK`, `active: false` | 401 |
| auth's dependency down (Redis, DB) | `UNAVAILABLE` | 503 — **retryable** |
| deadline exceeded | `DEADLINE_EXCEEDED` | 503 |
| bug inside auth | `INTERNAL` | 502 — not retryable |
| method or message mismatch | `UNIMPLEMENTED` | 503, and **log loudly**: this means proto drift, not an outage |

`UNAVAILABLE` versus `INTERNAL` is the load-bearing distinction: only
`UNAVAILABLE` conventionally signals "retry". chat-service already retries on it
with backoff. auth-service previously returned `UNKNOWN` for dependency
failures, which silently disabled that path — fixed 2026-10-26.

### Error envelopes — three different shapes on this platform

| Emitted by | Shape |
|---|---|
| auth-service | `{"detail": "..."}` — no status, no code (`auth-service/core/views.py`) |
| chat-service | `{"error":{"code","message","details","request_id"}}` — **nested** (`chat/api/errors.py`) |
| cloud-gateway | `{"timestamp","status","error","code","message","path","requestId"}` — flat (`error/GatewayError.java`) |

**This service emits cloud-gateway's flat shape (FR15), extended with `details`
for FR11's field-level 400s.** It is the only shape that *already* appears on
`/api/messages/**`: the gateway's own 503 `NO_INSTANCES`, 504 `UPSTREAM_TIMEOUT`
and 404 `ROUTE_NOT_FOUND` bodies, which Step 43's 20 s `response-timeout` makes
more visible, not less. Matching it gives a client one parse path on this route,
whoever wrote the body. Auth's shape is recorded, not copied: it has no code to
branch on.

**Codes keep chat-service's spelling** (`TOKEN_INVALID`, `AUTH_UNAVAILABLE`,
`VALIDATION_FAILED`, `ROOM_NOT_FOUND`), already frozen as a v1 contract on
`/api/rooms/**`, so a client learns one vocabulary.

```json
{"timestamp":"…","status":401,"error":"Unauthorized","code":"TOKEN_INVALID","message":"...","path":"…","requestId":"..."}
```

Step 21 implements this as `ErrorResponse`; fields other than
`timestamp`/`code`/`message`/`requestId` are omitted when absent.

---

### Recorded output (Step 2)

Static facts above are read from the proto and are reproducible with
`sha256sum`. The two below need the live stack and must be run and pasted
verbatim.

```text
<!-- paste: positive ValidateToken (a freshly issued token) -> active: true, user_id set -->

<!-- paste: negative ValidateToken (tampered token) -> active: false -->
```
