# Waitlist-as-a-Service — Architecture & Design Decisions

Living document. Captures every locked decision and the reasoning behind it, so any future session (or interview) can pick up from here without re-deriving context. Append, don't rewrite — if a decision changes, note what it replaced and why.

---

## 1. Project Goal

Build a "Waitlist-as-a-Service" backend from scratch for an SDE resume/interview (target: Flipkart-style system design interview). The goal is depth of understanding, not feature count — every line should be defensible: the reason for the choice, the tradeoffs, what breaks at scale, and what alternatives exist.

**Working style:** discuss and lock architecture before writing any code. Depth over breadth — a few concepts defended cold beats many concepts name-dropped shallowly.

---

## 2. Tech Stack (Locked)

- **Backend:** Java Spring Boot
- **Live queue state:** Redis (Sorted Sets + Pub/Sub)
- **Persistence:** PostgreSQL
- **Real-time:** WebSocket with STOMP
- **Frontend:** Minimal React dashboard (demonstration only)

Go was considered and rejected — learning Go idioms would dilute the actual learning target (Redis, WebSockets, state machines, concurrency). Spring Boot is already familiar, so all mental bandwidth goes to architecture, not syntax.

---

## 3. Core Features

1. Join a waitlist and receive a live position number
2. Real-time position updates via WebSocket
3. Referral-based priority bumping — refer someone, move up N spots
4. Soft reservation when you reach position 1 — hold for X minutes, then auto-release if unconfirmed
5. Group joining — multiple people join and move as a unit
6. Basic React dashboard to visualize the live queue

---

## 4. Platform Philosophy: Generic Core, Flagship Stress-Test Scenario

The platform itself is **generic and multi-tenant** — any business running a waitlist (restaurant, theme park, cloud-capacity queue, product drop) is just a tenant with a config (`groupPolicy`, reservation window, priority rules).

**Product-drop / flash-sale waitlists (Nike SNKRS-style) are the flagship stress-test tenant profile.** Reasoning: it's the one real-world scenario where all three differentiating features — referral bumping, soft reservation, group joining — get exercised simultaneously, and it's the scenario most exposed to burst load and fraud. Every scale decision below is justified against "what if this tenant is a viral drop with 5M joins in 10 seconds," even though the platform doesn't hardcode that assumption.

### Other real-world analogs considered (inform specific decisions, not the core design)

| Use case | What it validates |
|---|---|
| Concert/flight virtual waiting rooms (Ticketmaster, Queue-it) | Admission control under thundering herd; soft reservation = checkout window |
| Product drops (Nike SNKRS, GPU/console restocks) | **Flagship.** Referral virality + fraud exposure |
| Theme park virtual queues (Disney Genie+) | Group joining, STRICT vs. PARTIAL policy |
| Cloud capacity/quota waitlists | Priority tiers, auditability |
| Gaming matchmaking lobbies | RESERVED → CONFIRMED → EXPIRED under latency pressure |

---

## 5. Core Architectural Decisions

**Decision 1 — Hybrid queue model.** Each product gets a fully isolated Redis Sorted Set keyed as `waitlist:{waitlistId}:queue`. Every entry also carries a `waitlistId` tag in PostgreSQL, enabling cross-waitlist queries ("all queues where user X is waiting," "all active waitlists") without touching Redis. Redis owns live queue state (speed layer); PostgreSQL owns the queryable persistent record (durability layer). If Redis crashes, the sorted set can be rebuilt from PostgreSQL.

> **Resolved (2026-07-18):** PostgreSQL must store a `join_sequence` column (integer, assigned at join time) as the score-equivalent anchor. ZSET score = `join_sequence` minus total bumps applied. Crash recovery replays every entry's `join_sequence` back into a fresh ZSET. See §15 (Data Flow) for how this is written in the hot path.

**Decision 2 — Bank referral credits within the same waitlist.** When A refers B and earns a bump, but A is already at position 1 or in soft reservation, the credit is banked in a `referral_credit` ledger per user per waitlist in PostgreSQL instead of being discarded. Credits only activate when the person is not already at the front. A state check runs before applying credits to prevent double-dipping.

**Decision 3 — Configurable group policy.** Each waitlist has a `groupPolicy` field: `STRICT` (whole group moves as a unit, nobody proceeds until all members reach the front together) or `PARTIAL` (members proceed individually as slots open). Group members track individual states even under `STRICT`, because you need to know who has confirmed vs. not once the front is reached.

---

## 6. Why Redis Sorted Set

A Sorted Set orders members by a floating-point score automatically. Lower score = higher position (position 1 = lowest score). A referral bump is one `ZINCRBY waitlist:{id}:queue -N {userId}` — O(log N), atomic, no locks. Storing queue order as PostgreSQL rows instead would require touching O(N) rows per bump — breaks at scale.

## 7. Why Redis + PostgreSQL Together (Not One or the Other)

Redis does sorted-set ops in O(log N) in memory, no disk — essential for join/bump/position-read in the hot path. PostgreSQL is the source of truth for durability, audit, cross-waitlist queries, and crash recovery. CQRS-adjacent split: Redis = write-optimized live state, PostgreSQL = durable queryable record.

## 8. Why Pub/Sub for Real-Time (Not Polling)

On any position change, the queue service publishes to `waitlist:{id}:events`. The broadcast service subscribes and fans out to WebSocket clients immediately. Decouples the write path from the notification path. Polling Redis from every client every second would mean thousands of requests/sec with mostly no new data.

> **Resolution (locked 2026-07-18):** In our build the publisher (Core Queue Service) and subscriber (WebSocket Gateway) are separate processes but the gap still doesn't bite us — if the gateway restarts, all WebSocket connections drop anyway and clients reconnect. On reconnect the client sends `{ userId, waitlistId }`; the gateway does `ZRANK` and immediately pushes current position. Client is fully re-synced in one round trip, no replay needed. The fire-and-forget gap only becomes real when multiple gateway instances run concurrently and some miss events — the upgrade path there is **Redis Streams** (durable ordered log, consumer groups with per-consumer offsets so a restarting instance resumes from its last `XACK`). That's a one-topology-step upgrade, documented in §12.2, not built yet.

---

## 9. State Machine — Six States of a Waitlist Entry

Every entry is in exactly one state at all times. Impossible state combinations are unrepresentable by design — the core argument for a state machine over boolean flags.

| State | Meaning |
|---|---|
| `WAITING` | In the queue, steady state |
| `BUMPED` | Transient — referral credit just applied, used for frontend animation, immediately returns to `WAITING` in the same transaction |
| `RESERVED` | Reached position 1, soft reservation timer running |
| `CONFIRMED` | User acted during the reservation window |
| `EXPIRED` | Reservation timer ran out, slot released |
| `CANCELLED` | User left voluntarily, reachable from `WAITING` or `BUMPED` |

**Transitions:** `WAITING → BUMPED → WAITING` (referral applied) · `WAITING → RESERVED` (reached position 1) · `RESERVED → CONFIRMED` (user acts) · `RESERVED → EXPIRED` (timeout) · `WAITING/BUMPED → CANCELLED` (voluntary exit)

---

## 10. Concurrency Scenarios Handled Explicitly

**Double-reservation race.** Two users simultaneously reach position 1 when only one reservation slot exists. Naive read-then-write across two operations lets both threads read "no reservation active" and both create one. **Fix:** Redis Lua scripting — the check-and-set runs as a single atomic server-side operation, no race window.

**Referral credit race.** Two referrals land simultaneously for the same person; both read the current balance, both write an update — one write is lost. **Fix:** optimistic locking in PostgreSQL via a version column; update only succeeds if the version matches what was read, otherwise retries. (Simpler alternative considered: atomic SQL `UPDATE credits = credits + N`, which avoids the lost-update problem without needing a version column or retry loop at all — worth using unless the credit-apply logic needs to read-modify-write more than a single increment.)

---

## 11. Redis Key Naming Conventions

- Live queue: `waitlist:{waitlistId}:queue` — Sorted Set, score = priority
- Event channel: `waitlist:{waitlistId}:events` — Pub/Sub channel for position-change broadcasts

---

## 12. Production Scale: System Design Concept Catalog

Full candidate list, grouped by where each concept lives in the stack. Each item is tagged with its **depth tier**, decided 2026-06-26:

- 🔵 **Deep design** — designed in full, defended cold, ideally built
- ⚪ **Documented, not over-engineered** — decision and tradeoff written down, built only at minimal viable depth
- 🟢 **Throughline** — runs through every other decision via back-of-envelope numbers, not a component itself

### 12.1 Traffic & Admission (the front door) — 🔵 Deep design
| Concept | Why it matters here |
|---|---|
| Rate limiting / token bucket per tenant+user | Stops one viral drop from starving others |
| Virtual waiting room (Kafka buffer before `ZADD`) | Absorbs the thundering herd at drop time before it touches Redis |
| Idempotency keys on join/refer/confirm | Client retries during a spike must not double-join or double-credit |
| Backpressure / load shedding | Fail fast with "try again" instead of timing out everyone |

### 12.2 Real-Time Delivery — 🔵 Deep design
| Concept | Why it matters here |
|---|---|
| Redis Pub/Sub → Redis Streams/Kafka | Pub/Sub drops messages for disconnected subscribers; Streams/Kafka give replay + consumer offsets |
| WebSocket gateway horizontal scaling | One node maxes out around tens of thousands of sockets; needs a stateless gateway tier |
| Sticky sessions / connection-aware load balancing | Predictable routing so reconnects don't lose state |

### 12.3 Data Layer at Scale — ⚪ Documented, not over-engineered
| Concept | Why it matters here |
|---|---|
| Redis Cluster sharding + hot-key strategy | One viral waitlist = one key = one shard regardless of cluster size; know when bucketed sub-ZSETs are needed vs. unnecessary |
| Postgres partitioning (by waitlist_id/time) | Billions of historical rows in one table kills query/index performance |
| Read replicas | Cross-waitlist analytics shouldn't compete with write-path traffic |
| Archival/cold storage for completed waitlists | OLTP table shouldn't grow forever |
| Outbox pattern / CDC for Redis↔Postgres consistency | Makes the eventually-consistent dual-write gap in Decision 1 explicit instead of hand-waved |

### 12.4 Reliability & Concurrency — ⚪ Documented, not over-engineered
| Concept | Why it matters here |
|---|---|
| Event-driven expiry (Redis keyspace notifications) vs. polling sweeper | Polling all RESERVED rows every tick doesn't scale; push-based does |
| Distributed lock / single-consumer guarantee on expiry events | Multiple app instances must not double-process the same expiry |
| Circuit breakers between services | Queue Service shouldn't hang if Notification Service degrades |
| Retry + dead-letter queue | Failed credit/referral writes need a recovery path, not silent loss |

### 12.5 Multi-Tenancy & Isolation — ⚪ Documented, not over-engineered
| Concept | Why it matters here |
|---|---|
| Per-tenant quotas/rate limits | Noisy-neighbor protection |
| Tenant-tiering (shared cluster vs. dedicated for whale tenants) | Mirrors real SaaS infra economics |
| Config-driven policy generalization | `groupPolicy` is the existing pattern; extend the same approach for priority tiers, fraud sensitivity, reservation window per tenant |

### 12.6 Security & Abuse Prevention — 🔵 Deep design
| Concept | Why it matters here |
|---|---|
| Referral fraud detection (velocity limits, device/IP fingerprinting) | Directly protects the referral feature — self-referral bots are the #1 real-world abuse vector for this mechanic |
| AuthN/AuthZ (JWT) | Baseline |
| CAPTCHA / proof-of-work at join | Standard anti-bot measure for flash-sale-style joins |

### 12.7 Observability & Ops — ⚪ Documented, not over-engineered
| Concept | Why it matters here |
|---|---|
| Metrics (queue depth, join rate, p99 latency, expiry rate) | How you'd detect a thundering herd or a stuck sweeper in production |
| Distributed tracing | Join → Redis → Postgres → Pub/Sub → WebSocket is a 5-hop path |
| Structured logging + correlation IDs | Cheap, high-leverage baseline |

### 12.8 Availability & Disaster Recovery — ⚪ Documented, not over-engineered
| Concept | Why it matters here |
|---|---|
| Redis persistence (AOF) + replica failover | "Rebuild from Postgres" needs either this or a defined RPO |
| Graceful degradation (Redis down → read-only from Postgres) | Defines exact behavior during the rebuild window |
| Multi-AZ/region | Worth naming as a next step; not designed in depth for this project |

### 12.9 Capacity Planning — 🟢 Throughline
Back-of-envelope numbers — ops/sec a single Redis shard sustains, sockets per gateway node, rows/day at assumed scale — run through every decision above as the justification, rather than existing as their own component.

---

## 13. Service Boundaries & Deployment Units (Locked 2026-06-26)

Eight bounded contexts, split by **where scaling/failure characteristics actually diverge** — not split-everything-into-microservices by default, and not one undifferentiated monolith either.

| Domain | Owns | Touches |
|---|---|---|
| Queue | Join, position read, applying a bump to the ZSET | Redis ZSET, Postgres `waitlist_entry` |
| Reservation | Position-1 handoff: create reservation, confirm, expire | Redis (Lua check-and-set, TTL), Postgres |
| Referral/Credit | Referral ledger, eligibility check, banking, applying banked credit | Postgres `referral_credit`, calls back into Queue to apply the bump |
| Group | Group membership, STRICT/PARTIAL policy, per-member confirmation state | Postgres; ZSET representation still open, see [§14](#14-open--not-yet-decided) |
| Admission/Gateway | AuthN, per-tenant rate limiting, idempotency-key dedup, absorbing the thundering herd | Edge only — forwards validated requests inward |
| Broadcast | Subscribes to position-change events, decides what to fan out | Event backbone (Pub/Sub → Streams/Kafka) |
| WebSocket Gateway | Holds client connections, pushes messages down | Receives from Broadcast only |
| Fraud/Abuse | Referral velocity checks, device/IP fingerprinting | Called by Referral before a credit is applied |
| Tenant/Admin | CRUD for waitlist config — groupPolicy, reservation window, priority rules | Postgres, low volume |

**Actual deployment (locked 2026-07-18) — two services, four Docker containers:**

| Container | What it is |
|---|---|
| `core-queue-service` | Spring Boot monolith: Queue + Reservation + Referral/Credit + Group + Tenant/Admin modules, plus rate-limiting and idempotency middleware |
| `websocket-gateway` | Spring Boot app: holds all client WebSocket/STOMP connections, subscribes to `waitlist:{id}:events`, pushes position updates |
| `redis` | Sorted Sets (live queue state) + Pub/Sub (event channel) |
| `postgres` | Durable record, credit ledger, cross-waitlist queries |

**Why exactly this split and no further:** The WebSocket Gateway is the one domain with a genuinely different scaling axis — it scales on *concurrent connections*, completely independent of business logic throughput. A quiet waitlist with 500K idle watchers costs as much gateway capacity as a busy one. It is also fully stateless (Redis holds all queue state), so running multiple gateway instances behind a load balancer requires zero Core Service changes. That earns a real service boundary. Everything else in the Core scales 1:1 with join volume, shares the same Redis key and Postgres row in the same logical operation — splitting them today adds network hops with no benefit.

**What stays as documented scale-out extractions (not built):** Admission/Gateway as a standalone service (rate limiting lives as middleware in Core for now), dedicated Broadcast Service, Fraud/Abuse Service. Each has a documented extraction trigger — the specific scaling pressure that would justify the split.

**Interview framing:** "I designed the system with eight bounded contexts and identified the one that has a genuinely different scaling axis. The other seven are co-deployed today with clear module boundaries that map 1:1 to microservices — here's exactly what scaling pressure would cause me to extract each one."

---

## 14. Build Scope: What We're Building vs. What We've Designed

The most important line to draw for a resume project. A finished, demo-able, defensible project beats a sprawling one that half-works.

### What we're building

| Feature / Component | Why it's in scope |
|---|---|
| Join a waitlist → live position number | Core value prop |
| Real-time position updates via WebSocket | The "wow" moment in a demo |
| Referral bump with atomic Lua script | Genuine concurrency problem with a concrete proof |
| Soft reservation state machine (WAITING → RESERVED → CONFIRMED / EXPIRED) | State machine over boolean flags; real interview topic |
| Group joining with STRICT / PARTIAL policy | Config-driven policy design |
| Core Queue Service (Spring Boot modular monolith) | All business logic, clean package boundaries reflecting eight domains |
| WebSocket Gateway (separate Spring Boot app) | The one justified microservice split |
| Docker Compose: one-command full-stack startup | Non-negotiable for anyone trying to run it |
| Redis (Sorted Sets + Pub/Sub) | Core architectural choice, fully exercised |
| PostgreSQL (durable record + credit ledger) | Dual-layer durability story, cross-waitlist queries |
| Basic React dashboard | Makes it a live demo, not just a Postman collection |

### What we've designed but are not building

These exist in this document as documented architectural extensions. The interview answer: "I've already thought through these — here's the specific trigger that would cause me to build each one."

| Extension | Trigger to build it |
|---|---|
| Kafka admission buffer | Join volume spikes saturate sustainable Redis ZADD throughput |
| Redis Streams (Pub/Sub upgrade) | Horizontal WebSocket Gateway scaling causes message drop |
| Dedicated Fraud / Abuse Service | Referral velocity checks become a bottleneck in Core |
| Dedicated Broadcast Service | Event fan-out volume decouples from Core write volume |
| CDC / outbox pattern | Reconciliation job proves insufficient for the inconsistency window |
| Redis Cluster + hot-key sharding | A single waitlist saturates one Redis node's write capacity |
| Postgres partitioning / read replicas | Table scans degrade at observed row counts |
| Multi-region | Business requirement, not a scaling requirement |

---

## 15. Data Flow: Join Operation (Locked 2026-07-18)

Tracing a single user joining a product-drop waitlist end-to-end.

**Step 1 — Client → Core Queue Service**
`POST /waitlists/{id}/join` with JWT + client-generated idempotency key. Rate-limiting middleware checks: token valid? idempotency key seen before? per-tenant+user rate limit exceeded? Any failure → reject at the edge, nothing touches Redis or Postgres.

**Step 2 — Two writes, one order: Redis first**
- `ZADD waitlist:{id}:queue {join_sequence} {userId}` — user is live in the queue, position readable instantly. `join_sequence` is an auto-incrementing integer assigned at write time.
- Insert `waitlist_entry` row in Postgres immediately after, with `join_sequence` stored as a column. This is the crash-recovery anchor — replaying every row's `join_sequence` back into a fresh ZSET perfectly reconstructs queue order.
- If Postgres write fails: retry with backoff. If retries exhaust: a periodic reconciliation job detects ZSET members with no backing Postgres row and backfills. Bounded inconsistency window, not silent data loss.
- Return `202 Accepted` to client. Position confirmation arrives via WebSocket.

**Step 3 — Publish `PositionAssigned` to `waitlist:{id}:events`**
A plain join appends at the back — no other user's position changes, so only the joining user needs a notification. (A bump is different: it changes the bumped user's score and shifts everyone between old and new position — heavier fan-out, separate trace.)

**Step 4 — WebSocket Gateway pushes the update**
Subscribed to `waitlist:{id}:events`. Looks up the joining user's open connection and pushes a STOMP frame: `{ userId, waitlistId, position, state: "WAITING" }`. Client spinner resolves.

**Step 5 — Reconnect handling**
On WebSocket reconnect, client sends `{ userId, waitlistId }`. Gateway calls `ZRANK waitlist:{id}:queue {userId}` and pushes current position immediately. Fully re-synced in one round trip — no replay, no Pub/Sub durability dependency.

---

## 16. Scalability Claim Framing

**Never say:** "This handles millions of users."
**Say instead:** "Designed for horizontal scalability — here are the specific inflection points and what I'd change at each."

### Three concrete proofs

| Proof | What it demonstrates |
|---|---|
| Concurrency test: 100 simultaneous reservation requests → assert exactly 1 succeeds | Show it breaking without the Lua script, show it always passing with it. Most interviewers have never seen a candidate bring a live race condition demo. |
| k6 / JMeter: 500 concurrent joins, flat p99 latency curve, screenshot in README | Evidence that O(log N) ZADD actually holds under load — a data-backed claim, not a vague one. |
| This architecture document in the repo | "I identified the scale-out inflection points before writing line one of code." Proof of thinking, not just talking. |

### The "what breaks at 10x" answer

"The WebSocket Gateway hits connection limits first — a single JVM holds around 50–100K concurrent connections. The gateway is stateless so I'd run multiple instances behind a load balancer; they all subscribe to the same Redis event channel, zero Core Service changes needed. Beyond that, a single viral waitlist is one Redis key on one shard regardless of cluster size — `ZADD` is still fast, but you can't distribute a single key. At that point I'd look at bucketed sub-ZSETs merged on read, or accept that Redis single-key throughput handles all but the most extreme cases."

---

## 17. Resume Project Polish Checklist

Ordered by impact. Do these after the core features work.

**Ships the interview:**
- [ ] `docker compose up` starts the full stack with seed data — zero manual steps
- [ ] Demo GIF in README: two browser windows, live position update on join, position jump on referral, reservation countdown on reaching position 1
- [ ] Live hosted link (Railway / Fly.io free tier) — "try it here" ends conversations before they start

**Wins the technical round:**
- [ ] Concurrency proof test: 100 concurrent reservation requests, assert exactly 1 succeeds — run twice, once with Lua script commented out (show the race), once with it (show the fix)
- [ ] k6 load test + one latency graph: 500 concurrent joins, flat p99 — screenshot in README
- [ ] GitHub Actions CI: run tests on every push, green badge on README

**Shows architectural discipline:**
- [ ] Package structure mirrors bounded contexts: `com.waas.queue`, `com.waas.reservation`, `com.waas.referral`, `com.waas.group` — no wrong-direction cross-package dependencies
- [ ] OpenAPI / Swagger on Core Service (one Spring Boot dependency, free browsable API docs)
- [ ] `ARCHITECTURE.md` in repo root — most projects have nothing; a reasoned design doc with a built vs. designed split is itself a differentiator

---

## 18. Open / Not Yet Decided

- How a `STRICT` group occupies the Redis Sorted Set — likely a single `group:{groupId}` member with individual member states tracked in Postgres; needs confirmation before schema design
- Full PostgreSQL schema — entity definitions, columns, foreign keys, indexes
- Spring Boot project skeleton and module structure
- Any code whatsoever

**Next step:** PostgreSQL schema design, anchored on `join_sequence` as the score-reconstruction column (locked in §15).

---

## 19. Teaching Style Preference

Explain every architectural decision with: the reason for the choice, the tradeoffs, what would break at scale, and what alternatives exist. Never just give code — make it understandable enough to defend in a Flipkart interview. Discuss and lock architecture before writing any code.

---

*Last updated: 2026-07-18*
