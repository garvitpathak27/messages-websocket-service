# messages-websocket-service

System of record for chat message content, and the live delivery channel for it.
Registers in Eureka as `messages-websocket-service`. REST at `/api/messages`, STOMP at `/ws`.
Clients reach it only through cloud-gateway on `:8090`.

- Build guide: `hello/helloworld__22/chat-messaging-service-docs/00-roadmap.md`
- Requirements: `hello/helloworld__22/chat-messaging-service-docs/chat-messages-service-spec.md`
- Platform decisions: `hello/helloworld__22/DECISIONS.md`
- Deferred work: `hello/helloworld__22/V2-BACKLOG.md`
- Contracts with other services: `docs/contracts.md`

## Trust boundary

This service assumes membership of the `chatapp` Docker network is trusted. It
validates every token itself and does not rely on the gateway for
authentication. Anything on that network can reach it directly, so no inbound
header is treated as proof of identity.

## Decisions (D1-D8)

Answers to the eight questions in spec §3. Code, docs and the build guide cite
them by ID. Changing a decision means revisiting every step in its last column.

| ID | Question | Decision | Rejected, and why | Consumed in steps |
|---|---|---|---|---|
| D1 | Who writes messages: REST or WebSocket? | REST `POST /api/messages` is the only write path. After the insert the service publishes `MESSAGE_CREATED`; its own STOMP layer pushes that event to `/topic/rooms.{roomId}`. Client STOMP `SEND` frames are rejected. The two halves communicate only through RabbitMQ, so this is two services sharing a deployable - see Step 41, "Keeping the delivery layer extractable". | Writes over STOMP `SEND` (spec Option B): STOMP has no status codes, so the per-endpoint status table of spec §9 and the FR15 envelope would need a parallel error channel (`/user/queue/errors`), and a STOMP `ERROR` frame closes the connection. Option B can be added later without changing the REST API. Splitting delivery into its own service now: considered and declined, see V2-BACKLOG. | 28, 37, 41 |
| D2 | How is room membership checked? | Per request: HTTP GET of chat-service's room detail via `lb://chat-service` (Eureka), forwarding the caller's `Authorization`. 200 means member, with the role in `my_role` (`admin` / `member`). 404 means not a member or no such room. No cache in v1. | A cache is the right end state and is already designed (DEC-2). `MEMBER_REMOVED` and `ROOM_DELETED` both exist and are already published by chat-service after commit (`chat/events.py:38`, `chat/services.py:335`), so **no chat-service work is required** - the roadmap's deferred table saying otherwise is stale. Deferred purely as v1 scope. A plain 30-60 s TTL with no invalidation is separately rejected: a removed member keeps access for the whole window, and FR9 grants no such window. | 3, 25, 31, 40 |
| D3 | How are tokens validated? | gRPC `ValidateToken` on `auth-service:50051` (Compose DNS), for every REST request and every STOMP `CONNECT`. Auth unreachable or deadline exceeded gives 503; never allow. Note that chat-service validates the same token again on the membership call, so one client request causes two validations - accepted for v1 (DECISIONS, SD D-9). | Verifying JWTs locally with a copied signing key: the platform signs with HS256, so this would mean shipping the symmetric secret to every service, and any one compromise could then **mint** tokens rather than merely verify them. Local verification becomes correct only after the RS256 migration (DEC-1, deferred) - and even then must still do a Redis revocation lookup, because a JWT cannot be un-issued. Trusting the gateway: the gateway only routes; gateway-level auth is deferred and its prerequisite is network lockdown. | 2, 20, 22, 39 |
| D4 | Pagination style | Cursor-based. The cursor is opaque base64url of `(createdAt epoch-millis, ObjectId hex)`. `before` returns older messages newest first; `after` returns newer ones oldest first; fetch `limit + 1` to compute `hasMore`. Default 50, max 100. | Page numbers / offsets: rows shift as messages arrive, producing duplicates or gaps (spec D4). ID-only cursor (spec §8 fallback): ObjectIds from two instances are not ordered within one second, so `before` can skip a message. | 15, 16, 29 |
| D5 | Delete semantics | Soft delete per message: set `deletedAt` and `deletedBy`. Reads return a tombstone (`content: null`, `deleted: true`). Deleting an already-deleted message succeeds (idempotent). | Hard delete: history loses the entry entirely, which FR8 forbids. Clients that already rendered the message get nothing to replace it with, and later moderation has no record (spec D5). Room-level cleanup on `ROOM_DELETED` is a separate choice (hard delete, Step 34). | 12, 29, 31 |
| D6 | Message ordering and IDs | `_id` is a MongoDB ObjectId, exposed as the string `id`. `createdAt` comes from the server clock in UTC, never from the request. The total order is `(createdAt, _id)`. | Client-supplied timestamps: a skewed or hostile clock reorders history (NFR6). Ordering by `_id` alone: see D4. | 12, 13, 16 |
| D7 | Idempotency | Each send carries `clientMessageId` (a UUID). A unique **partial** index covers `(roomId, senderId, clientMessageId)` where the field exists. If the insert hits `DuplicateKeyException`, the service loads the stored message and returns it with 200; a first insert returns 201. | Check-then-insert (find, insert if absent): two concurrent retries both see "absent" and both insert. A key on `clientMessageId` alone: one user's UUID can collide with another's and return someone else's message. A plain (non-partial) unique index: MongoDB treats a missing field as `null` and allows only one such document, so the second id-less message in a room would fail with `E11000` - which looks like broken idempotency but is a broken index. | 13, 14, 28 |
| D8 | Data types for foreign identifiers | `senderId` is auth's user id string, taken only from `ValidateToken`. `roomId` is chat-service's room UUID string (e.g. `93359bfa-3f1d-4544-bb6a-5a303689e159`). It is stored as a string and validated as a UUID on input. | Storing `roomId` as BSON binary UUID: couples this schema to chat-service's key type, which spec D8 forbids. Skipping format validation: a malformed id costs a chat-service round trip just to come back as 404, where FR11 wants a 400 naming the field. | 3, 12, 27 |

## Decisions with a recorded revisit path

Three of the above are v1 positions rather than permanent ones. Each has its
reasoning, trigger and starting point written down - see `DECISIONS.md` and
`V2-BACKLOG.md` at `hello/helloworld__22/`.

| ID | v1 position | Revisit when |
|---|---|---|
| D2 | no membership cache | chat-service availability or load becomes the constraint. The invalidation events already exist. |
| D3 | gRPC validation per request | after the RS256 migration (DEC-1), which makes local verification safe |
| D1 | one deployable | Step 53's load check shows the two halves scaling differently |

## Settled facts this service depends on

Verified against the running stack, not assumed. Step 2 and Step 3 re-verify and
record them in `docs/contracts.md`.

| Fact | Where |
|---|---|
| chat-service exposes `my_role` on the room serializer, values `admin` / `member` | `chat-service/chat/serializers.py:51` |
| Room ids are UUID strings | observed in chat-service responses |
| auth-service's error envelope is a flat `{"detail": "..."}` with no code; chat-service's is nested `{"error":{"code","message","details","request_id"}}` | `auth-service/core/views.py`, `chat-service/chat/api/errors.py` |
| This service emits **cloud-gateway's** flat envelope (`timestamp, status, error, code, message, path, requestId`, plus `details`), with chat-service's code spelling (FR15) | `docs/contracts.md`, Step 21 |
| chat-service room detail is `/api/rooms/{roomId}/` (trailing slash required); `ROOM_DELETED` goes to exchange `chat.events`, key `chat.events.room_deleted`, room id in `payload.room_id` | `docs/contracts.md` → Pinned values |
| The gateway sends **no** correlation header - this service generates its own | cloud-gateway `default-filters` |
| chat-service publishes `MEMBER_REMOVED` and `ROOM_DELETED` after commit | `chat/events.py:38`, `chat/services.py:335` |
| The event envelope contract (ADR-012) has a working validator | `chat-service/scripts/e2e/assert_event.py` |
| Platform test user | `alice+e2e@example.com` / `TestPass123!` |
