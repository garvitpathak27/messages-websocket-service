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

**From source** (reproducible without the stack):

```text
$ sha256sum auth-service/proto/auth.proto chat-service/contracts/auth.proto
90b67e67709216c367987e44d5d49d0e9c55dc2cd6102568f1cee1e626e0e5fa  auth-service/proto/auth.proto
90b67e67709216c367987e44d5d49d0e9c55dc2cd6102568f1cee1e626e0e5fa  chat-service/contracts/auth.proto

$ cat chat-service/contracts/auth.proto.sha256
90b67e67709216c367987e44d5d49d0e9c55dc2cd6102568f1cee1e626e0e5fa
```

Service `auth.AuthService`, method
`rpc ValidateToken ( .auth.TokenRequest ) returns ( .auth.TokenValidationResponse );`,
request `message TokenRequest { string access_token = 1; }` — read from the proto
above. Step 19 copies `auth-service/proto/auth.proto` and must reproduce this hash.

**Live probes — PENDING** (not yet run against the stack; paste verbatim when run,
never a token). Run from the repo root with the platform up:

```bash
PDIR="$PWD/auth-service/proto"
# negative (record this):
docker run --rm --network chatapp -v "$PDIR":/protos:ro fullstorydev/grpcurl:v1.9.1 -plaintext -emit-defaults \
  -import-path /protos -proto auth.proto -d '{"access_token":"garbage"}' auth-service:50051 auth.AuthService/ValidateToken
# expected: "active": false

# auth's own error shape (record this):
curl -s -X POST http://localhost:8090/api/v1/auth/login/ -H 'Content-Type: application/json' \
  -d '{"email":"alice+e2e@example.com","password":"wrong-on-purpose"}'
# expected: {"detail": "Invalid credentials."}

# positive (verify only, do NOT record — input is a live token):
TOKEN=$(./scripts/token.sh)   # or the login curl with the real password
docker run --rm --network chatapp -v "$PDIR":/protos:ro fullstorydev/grpcurl:v1.9.1 -plaintext -emit-defaults \
  -import-path /protos -proto auth.proto -d "{\"access_token\":\"$TOKEN\"}" auth-service:50051 auth.AuthService/ValidateToken | grep active
# expected: "active": true   — run this FIRST; false here means a proto/field mismatch, not a bad token
```

> The Phase 1 guide's "How to verify" sends `{"token": ...}`. That field does not
> exist; proto3 ignores it and the call returns `active: false` for a valid token.
> Use `access_token` as above.

```text
<!-- paste: negative ValidateToken output -->

<!-- paste: login failure body -->
```

---

## Chat service (Step 3)

- Room serializer exposes `my_role` (`chat-service/chat/serializers.py:51`,
  `RoomSerializer`), values `admin` / `member`. The FR7 admin rule uses it.
- Room ids are UUID strings, e.g. `93359bfa-3f1d-4544-bb6a-5a303689e159` (D8).
  The route converter is `<uuid:room_id>` (`chat/api/urls.py:18`), so validation
  here is a strict UUID check.
- Membership semantics (D2): room detail 200 = member (+ `my_role`); 404 = not a
  member **or** no such room. Both raise the same
  `NotFound(ROOM_NOT_FOUND_DETAIL, code=ErrorCode.ROOM_NOT_FOUND)`
  (`chat/permissions.py:39-45`), so they are indistinguishable by design.
- Called via `lb://chat-service` — the container IP Eureka holds, exactly as the
  gateway does. Never by Compose name: Django rejects `Host: chat-service:8001`
  unless `ALLOWED_HOSTS` lists it.
- Event envelope: ADR-012 (`chat-service/docs/architecture-decisions.md`), built
  only by `chat/events.py::build_envelope`:
  `{event_id, type, version: 1, timestamp, producer: "chat-service", payload}`.
  `timestamp` ends in a literal `Z` (`isoformat_utc`), so emit from `Instant`,
  never `OffsetDateTime`.
- Validator: `chat-service/scripts/e2e/assert_event.py`. It hard-codes the
  `chat.events.` routing prefix (line 53) and `producer == "chat-service"`, so it
  rejects this service's events unmodified — Step 36 adds `--producer` /
  `--routing-prefix` flags.
- chat-service declares the **exchange only** (`chat/messaging/topology.py`,
  ADR-006): topic, durable, not auto-delete. Queues and bindings belong to
  consumers — this service declares its own `ROOM_DELETED` queue (Step 34).
- Live `ROOM_DELETED` capture through `assert_event.py`: Step 36.

### Recorded output (Step 3)

**From source:**

```text
cloud-gateway/src/main/resources/application.yml
62:            - id: chat-route
63:              uri: lb://chat-service
64:              predicates:
65:                - Path=/api/rooms/**
66:              filters:
67:                - name: Retry          (only filter: no StripPrefix / RewritePath → public path == internal path)

chat-service/config/urls.py:16:    path("api/", include("chat.api.urls")),
chat-service/chat/api/urls.py:16:  path("rooms/", views.RoomListCreateView.as_view(), name="room-list"),
chat-service/chat/api/urls.py:18:  path("rooms/<uuid:room_id>/", views.RoomDetailView.as_view(), name="room-detail"),
  → internal room-detail path: /api/rooms/{roomId}/   (no v1; trailing slash required — APPEND_SLASH 301s without it)

chat-service/chat/events.py:36:    ROOM_DELETED = "ROOM_DELETED"
chat-service/chat/events.py:48-52: def routing_key_for(event_type): return f"chat.events.{event_type.lower()}"
chat-service/chat/services.py:219-228:
    publish_on_commit(
        EventType.ROOM_DELETED,
        {
            "room_id": str(room.pk),
            "type": room.type,
            "deleted_by": deleted_by,
            "deleted_at": isoformat_utc(room.deleted_at),
            "member_user_ids": member_user_ids,
        },
    )
  (payload.type is the ROOM type, direct/group — not the event type)

chat-service/chat/messaging/publisher.py:69:   exchange=settings.RABBITMQ_EXCHANGE,
chat-service/docker-compose.yml:31,94:         RABBITMQ_EXCHANGE: chat.events
chat-service/.env.example:34:                  RABBITMQ_EXCHANGE=chat.events
chat-service/chat/messaging/topology.py:15-17: EXCHANGE_TYPE = "topic"; EXCHANGE_DURABLE = True; EXCHANGE_AUTO_DELETE = False
```

**Live probes — PENDING** (run with the platform up; positive case first):

```bash
TOKEN=$(./scripts/token.sh)
ROOM_ID=$(curl -s http://localhost:8090/api/rooms/ -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json; d=json.load(sys.stdin); d=d.get("results",d) if isinstance(d,dict) else d; print(d[0]["id"])')
CHAT=$(curl -s -H 'Accept: application/json' http://127.0.0.1:8761/eureka/apps/CHAT-SERVICE | python3 -c 'import sys,json; i=json.load(sys.stdin)["application"]["instance"]; i=i if isinstance(i,list) else [i]; print(i[0]["ipAddr"]+":"+str(i[0]["port"]["$"]))')
docker run --rm --network chatapp curlimages/curl:8.10.1 -s -H "Authorization: Bearer $TOKEN" "http://$CHAT/api/rooms/$ROOM_ID/" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["id"], d["my_role"])'
# expected: <uuid> admin|member
docker run --rm --network chatapp curlimages/curl:8.10.1 -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "http://$CHAT/api/rooms/$(python3 -c 'import uuid; print(uuid.uuid4())')/"
# expected: 404
docker exec chat-app-rabbitmq-1 rabbitmqctl list_exchanges name type durable | grep chat.events
# expected: chat.events	topic	true
```

```text
<!-- paste: rabbitmqctl list_exchanges line for chat.events -->
```

### Pinned values (Step 3)

| Key | Value | Source |
|---|---|---|
| `CHAT_ROOM_DETAIL_PATH` | `/api/rooms/{roomId}/` | `config/urls.py:16` + `chat/api/urls.py:18`; gateway `chat-route` has no rewrite |
| `CHAT_EVENTS_EXCHANGE` | `chat.events` (topic, durable) | `chat/messaging/publisher.py:69` → `RABBITMQ_EXCHANGE`; `chat-service/docker-compose.yml:31`; `topology.py:15-17` |
| `ROOM_DELETED_ROUTING_KEY` | `chat.events.room_deleted` | `chat/events.py:48-52` (`routing_key_for`) |
| `ROOM_DELETED_ROOM_ID_FIELD` | `room_id` | `chat/services.py:222` |
| Event envelope | ADR-012, `chat-service/scripts/e2e/assert_event.py` | `chat/events.py:78-89` |
