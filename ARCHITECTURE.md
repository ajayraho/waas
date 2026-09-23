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

> **Revised (2026-09-23):** The implicit formula is replaced by an explicitly stored `queue_score` column, because the global sequence makes scores sparse, so "score − N" does not mean "N spots". Recovery is state-aware (WAITING rows → queue ZSET, RESERVED rows → reserved ZSET). See §21.3 and §21.6.

**Decision 2 — Bank referral credits within the same waitlist.** When A refers B and earns a bump, but A is already at position 1 or in soft reservation, the credit is banked in a `referral_credit` ledger per user per waitlist in PostgreSQL instead of being discarded. Credits only activate when the person is not already at the front. A state check runs before applying credits to prevent double-dipping.

> **Revised (2026-09-23):** "Is the user still in the queue?" and "apply the bump" are decided together in one Lua script, so there is no gap for a race between the check and the write. Banked credits are applied automatically when the user re-joins that waitlist. See §21.4.

**Decision 3 — Configurable group policy.** Each waitlist has a `groupPolicy` field: `STRICT` (whole group moves as a unit, nobody proceeds until all members reach the front together) or `PARTIAL` (members proceed individually as slots open). Group members track individual states even under `STRICT`, because you need to know who has confirmed vs. not once the front is reached.

---

## 6. Why Redis Sorted Set

A Sorted Set orders members by a floating-point score automatically. Lower score = higher position (position 1 = lowest score). A referral bump is one `ZINCRBY waitlist:{id}:queue -N {userId}` — O(log N), atomic, no locks. Storing queue order as PostgreSQL rows instead would require touching O(N) rows per bump — breaks at scale.

> **Revised (2026-09-23):** A bump is now **rank-based**: a Lua script moves the member exactly N ranks up by placing its score at the midpoint between its new neighbours. It is still O(log N) and atomic. Plain `ZINCRBY -N` only works when scores are dense, and ours are not. See §21.3.

## 7. Why Redis + PostgreSQL Together (Not One or the Other)

Redis does sorted-set ops in O(log N) in memory, no disk — essential for join/bump/position-read in the hot path. PostgreSQL is the source of truth for durability, audit, cross-waitlist queries, and crash recovery. CQRS-adjacent split: Redis = write-optimized live state, PostgreSQL = durable queryable record.

## 8. Why Pub/Sub for Real-Time (Not Polling)

On any position change, the queue service publishes to `waitlist:{id}:events`. The broadcast service subscribes and fans out to WebSocket clients immediately. Decouples the write path from the notification path. Polling Redis from every client every second would mean thousands of requests/sec with mostly no new data.

> **Resolution (locked 2026-07-18):** In our build the publisher (Core Queue Service) and subscriber (WebSocket Gateway) are separate processes but the gap still doesn't bite us — if the gateway restarts, all WebSocket connections drop anyway and clients reconnect. On reconnect the client sends `{ userId, waitlistId }`; the gateway does `ZRANK` and immediately pushes current position. Client is fully re-synced in one round trip, no replay needed. The fire-and-forget gap only becomes real when multiple gateway instances run concurrently and some miss events — the upgrade path there is **Redis Streams** (durable ordered log, consumer groups with per-consumer offsets so a restarting instance resumes from its last `XACK`). That's a one-topology-step upgrade, documented in §12.2, not built yet.

> **Corrected (2026-09-23):** Two fixes. (1) **Upgrade semantics:** WebSocket fan-out needs *broadcast*, not a shared consumer group. A shared group load-balances each event to one gateway, which usually isn't the one holding the user's socket, so the event is silently dropped. Each gateway instance must read the full stream with its own offset. See §21.9. (2) **What "any position change" means:** Core never publishes one event per passively shifted user. Positions are computed on read (`ZRANK`); gateways recompute positions for their own connections when a waitlist is marked dirty. See §21.7.

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

> **Revised (2026-09-23) — five states, BUMPED is an event.** BUMPED never persists (it reverts in the same transaction), so it is removed from the DB state enum and emitted as a `BUMPED` fan-out event instead. "Reached position 1" is replaced by **"promoted into one of `serving_capacity` slots"**. `RESERVED → CANCELLED` (user declines) is added; it frees the slot. Revised transitions: `WAITING → RESERVED` (promoted) · `RESERVED → CONFIRMED` · `RESERVED → EXPIRED` · `WAITING/RESERVED → CANCELLED`. See §21.1 and §21.11.

---

## 10. Concurrency Scenarios Handled Explicitly

**Double-reservation race.** Two users simultaneously reach position 1 when only one reservation slot exists. Naive read-then-write across two operations lets both threads read "no reservation active" and both create one. **Fix:** Redis Lua scripting — the check-and-set runs as a single atomic server-side operation, no race window.

**Referral credit race.** Two referrals land simultaneously for the same person; both read the current balance, both write an update — one write is lost. **Fix:** optimistic locking in PostgreSQL via a version column; update only succeeds if the version matches what was read, otherwise retries. (Simpler alternative considered: atomic SQL `UPDATE credits = credits + N`, which avoids the lost-update problem without needing a version column or retry loop at all — worth using unless the credit-apply logic needs to read-modify-write more than a single increment.)

> **Revised (2026-09-23):** (1) Double-reservation generalises to **capacity C**: the guard is "slots remaining", and the proof is "100 concurrent attempts → exactly C reserved". See §21.1. (2) The optimistic-locking/version-column approach is **dropped**. Counters use atomic increments. The race that actually matters is the *banking decision* (apply vs. bank), which is a check-then-act on queue membership. It is closed by deciding it inside the bump Lua script and then writing to Postgres with conditional `UPDATE ... WHERE state = 'WAITING'`. See §21.4.

---

## 11. Redis Key Naming Conventions

- Live queue: `waitlist:{waitlistId}:queue` — Sorted Set, score = priority
- Event channel: `waitlist:{waitlistId}:events` — Pub/Sub channel for position-change broadcasts
- Reserved set *(added 2026-09-23)*: `waitlist:{waitlistId}:reserved` — Sorted Set of members currently holding a slot, **score = reservation expiry (epoch ms)**. Doubles as the expiry index for the sweeper. See §21.1–§21.2.
- Hash tag note: when moving to Redis Cluster, name keys `waitlist:{<waitlistId>}:queue` (the braces form a hash tag) so a waitlist's `queue` and `reserved` keys land on the same slot, because the Lua scripts touch both.

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

> **Added (2026-09-23):** Serving capacity + `promote` Lua script (§21.1), a `@Scheduled` expiry sweeper over the reserved ZSET (§21.2), rank-based bump (§21.3), bidirectional reconciliation job (§21.6), and a tenant access guard (§21.10) are all **in build scope**. The reservation feature cannot be demoed without them. Keyspace-notification expiry remains a documented upgrade.

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

> **Corrected (2026-09-23):** "Edge" is overstated. The middleware runs *inside* Core, so a herd still uses Core's HTTP threads before rejection. It is rejected *before Redis/Postgres*. Real edge shedding happens at the LB/API gateway, which is the reason the Admission service extraction exists.

**Step 2 — Two writes, one order: Redis first**
- `ZADD waitlist:{id}:queue {join_sequence} {userId}` — user is live in the queue, position readable instantly. `join_sequence` is an auto-incrementing integer assigned at write time.
- Insert `waitlist_entry` row in Postgres immediately after, with `join_sequence` stored as a column. This is the crash-recovery anchor — replaying every row's `join_sequence` back into a fresh ZSET perfectly reconstructs queue order.
- If Postgres write fails: retry with backoff. If retries exhaust: a periodic reconciliation job detects ZSET members with no backing Postgres row and backfills. Bounded inconsistency window, not silent data loss.
- Return `202 Accepted` to client. Position confirmation arrives via WebSocket.

> **Corrected (2026-09-23) — Postgres first.** The bullets above are impossible as written: `join_sequence` comes from `nextval()` during the Postgres INSERT, so it can't be ZADDed before it exists. (init.sql already assumed `RETURNING`.) Revised order: `INSERT ... RETURNING join_sequence` → `ZADD queue {queue_score} {member}` → apply any banked credits → run `promote`. If ZADD fails, the durable row exists without a ZSET member, and the reconciliation job adds it. A durable record is never missing; Redis is always derivable. See §21.5.

**Step 3 — Publish `PositionAssigned` to `waitlist:{id}:events`**
A plain join appends at the back — no other user's position changes, so only the joining user needs a notification. (A bump is different: it changes the bumped user's score and shifts everyone between old and new position — heavier fan-out, separate trace.)

> **Resolved (2026-09-23):** Core does not fan out to passively shifted users. Every event marks the waitlist *dirty*; each gateway recomputes positions for **its own** connections on that waitlist at most once per tick. The per-bump cost is bounded by connected users, not by bump distance, and events are coalesced under a referral storm. See §21.7.

**Step 4 — WebSocket Gateway pushes the update**
Subscribed to `waitlist:{id}:events`. Looks up the joining user's open connection and pushes a STOMP frame: `{ userId, waitlistId, position, state: "WAITING" }`. Client spinner resolves.

**Step 5 — Reconnect handling**
On WebSocket reconnect, client sends `{ userId, waitlistId }`. Gateway calls `ZRANK waitlist:{id}:queue {userId}` and pushes current position immediately. Fully re-synced in one round trip — no replay, no Pub/Sub durability dependency.

> **Corrected (2026-09-23):** `ZRANK` alone breaks for RESERVED users, who are no longer in the queue ZSET and need their countdown. Resync = pipelined `ZSCORE queue` + `ZSCORE reserved` (the score *is* the expiry), with a Postgres lookup only when the user is in neither. See §21.8.

---

## 16. Scalability Claim Framing

**Never say:** "This handles millions of users."
**Say instead:** "Designed for horizontal scalability — here are the specific inflection points and what I'd change at each."

### Three concrete proofs

| Proof | What it demonstrates |
|---|---|
| Concurrency test: 100 simultaneous reservation requests → assert exactly 1 succeeds *(revised 2026-09-23: exactly C = `serving_capacity` succeed; §21.1)* | Show it breaking without the Lua script, show it always passing with it. Most interviewers have never seen a candidate bring a live race condition demo. |
| k6 / JMeter: 500 concurrent joins, flat p99 latency curve, screenshot in README | Evidence that O(log N) ZADD actually holds under load — a data-backed claim, not a vague one. |
| This architecture document in the repo | "I identified the scale-out inflection points before writing line one of code." Proof of thinking, not just talking. |

### The "what breaks at 10x" answer

"The WebSocket Gateway hits connection limits first — a single JVM holds around 50–100K concurrent connections. The gateway is stateless so I'd run multiple instances behind a load balancer; they all subscribe to the same Redis event channel, zero Core Service changes needed. Beyond that, a single viral waitlist is one Redis key on one shard regardless of cluster size — `ZADD` is still fast, but you can't distribute a single key. At that point I'd look at bucketed sub-ZSETs merged on read, or accept that Redis single-key throughput handles all but the most extreme cases."

> **Corrected (2026-09-23):** (1) **Sockets/JVM:** 50–100K assumes a non-blocking connector (Tomcat NIO, the default, or Netty) plus OS file-descriptor and heap tuning. The limit is memory per session, not threads. (2) **Hot key:** *hash*-bucketed sub-ZSETs break global rank, because hash buckets have no order relative to each other. The defensible answers are **score-range sharding** (buckets are ordered, so global rank = local rank + sum of earlier bucket sizes, kept in a small counter set) or **approximate positions** ("~2,300th") at extreme scale. See §21.12.

---

## 17. Resume Project Polish Checklist

Ordered by impact. Do these after the core features work.

**Ships the interview:**
- [ ] `docker compose up` starts the full stack with seed data — zero manual steps
- [ ] Demo GIF in README: two browser windows, live position update on join, position jump on referral, reservation countdown on reaching position 1
- [ ] Live hosted link (Railway / Fly.io free tier) — "try it here" ends conversations before they start

**Wins the technical round:**
- [ ] Concurrency proof test: 100 concurrent reservation requests, assert exactly C (`serving_capacity`) succeed — run twice, once with Lua script commented out (show the race), once with it (show the fix)
- [ ] k6 load test + one latency graph: 500 concurrent joins, flat p99 — screenshot in README
- [ ] GitHub Actions CI: run tests on every push, green badge on README

**Shows architectural discipline:**
- [ ] Package structure mirrors bounded contexts: `com.waas.queue`, `com.waas.reservation`, `com.waas.referral`, `com.waas.group` — no wrong-direction cross-package dependencies
- [ ] OpenAPI / Swagger on Core Service (one Spring Boot dependency, free browsable API docs)
- [ ] `ARCHITECTURE.md` in repo root — most projects have nothing; a reasoned design doc with a built vs. designed split is itself a differentiator

---

## 18. PostgreSQL Schema Design (Locked 2026-07-18)

Full SQL in `init.sql` (Docker init script — runs once on first container startup). Key design decisions explained here.

**Eight tables, in dependency order:** `tenant` → `waitlist` → `app_user` → `waitlist_group` → `waitlist_entry` → `group_member` → `referral` → `referral_credit`.

### Notable decisions worth defending in an interview

**`join_sequence` uses a global PostgreSQL sequence, not a per-waitlist counter.**
`DEFAULT nextval('waitlist_join_seq')` auto-assigns on every INSERT. The app gets the value back via `RETURNING join_sequence` and immediately uses it as the Redis `ZADD` score. Globally monotonic is fine — scores are only compared within a single waitlist's ZSET. A per-waitlist counter would require an extra row-lock on a counter table for every join, under burst load that's a bottleneck.

**ZSET score formula is stored implicitly, not as a column.**
`score = join_sequence - total_bumps_applied`. Both integers are on `waitlist_entry`. Crash recovery replays every row's computed score into a fresh ZSET. `total_bumps_applied` is updated atomically: `UPDATE waitlist_entry SET total_bumps_applied = total_bumps_applied + $n` — a single SQL statement, no optimistic locking needed for this specific update.

> **Superseded (2026-09-23):** Score is stored explicitly as `queue_score DOUBLE PRECISION` (initialised to `join_sequence`, rewritten by rank-based bumps). `total_bumps_applied` stays as an audit counter only. See §21.3.

**Partial unique index allows re-joining after terminal states.**
```sql
CREATE UNIQUE INDEX idx_waitlist_entry_active
    ON waitlist_entry(user_id, waitlist_id)
    WHERE state NOT IN ('CANCELLED', 'EXPIRED');
```
A standard UNIQUE constraint would prevent re-joining after cancellation. The partial index makes re-joining possible while still preventing duplicate active entries — a non-obvious but correct choice.

**State machine invariants enforced at the database level, not just application level.**
```sql
CONSTRAINT chk_reserved_needs_expiry
    CHECK (state != 'RESERVED' OR reservation_expires_at IS NOT NULL)
```
Three such constraints on `waitlist_entry`. If a bug in the application creates an invalid state, the database rejects the write. "Impossible states are unrepresentable" applied at two layers.

**STRICT vs PARTIAL groups map to rows differently.**
- STRICT: one `waitlist_entry` row per group (owned by creator, `group_id` set). ZSET member = `group:{groupId}`. Individual confirmation tracked in `group_member.has_confirmed`. Group transitions to CONFIRMED only when all `has_confirmed = TRUE`.
- PARTIAL: one `waitlist_entry` row per member (`group_id` set on each). Each member advances independently. `group_member` records membership for display.

**`referral_credit` splits credits into three columns.**
`pending_credits` (banked, not yet applied), `total_credits_earned` (lifetime, never decremented), `total_credits_applied` (how much has been subtracted from ZSET score). The split enables: correct banking logic, full audit trail, and analytics on referral effectiveness — with zero ambiguity about which credits are in which state.

**Self-referral rejected at the database level.**
```sql
CONSTRAINT chk_no_self_referral CHECK (referrer_id != referee_id)
```
The most obvious fraud vector, caught before it reaches application code.

**Seed data tells the demo story without any manual setup.**
Alice joined last (sequence 5) but referred 3 people (bump_amount=2, so 6 bumps applied). Her ZSET score = 5 − 6 = −1, putting her at position 1 ahead of everyone. This makes the referral-bump feature immediately visible the moment you open the dashboard after `docker compose up`.

> **To update (2026-09-23):** The seed only works because it's dense. It must set `queue_score` explicitly, drop any use of `BUMPED`, and seed one RESERVED entry so the countdown is visible on first load. The full list of pending init.sql changes is in §21.13.

---

## 19. Open / Not Yet Decided

- Spring Boot project structure — module layout, package naming, Docker Compose wiring
- Any application code

**Next step:** Spring Boot project skeleton — module structure mirroring the eight bounded contexts, Docker Compose file wiring both services to Redis and Postgres, then start with the Queue module.

> **Update (2026-09-24):** Brick 1 (foundations) is done, see §22. `init.sql` is superseded by the Flyway migrations. **Next: Brick 2 = Queue module** (join, position read, startup rebuild, events). *(All bricks + proofs/CI done, see §22.)*
>
> **Updated (2026-09-23):** Before the skeleton, apply the pending init.sql changes in §21.13. Build order after the skeleton: Queue (join + position) → `promote` + Reservation + sweeper → Referral (rank bump + banking) → Group → gateway fan-out.

---

---

## 20. Teaching Style Preference

Explain every architectural decision with: the reason for the choice, the tradeoffs, what would break at scale, and what alternatives exist. Never just give code — make it understandable enough to defend in a Flipkart interview. Discuss and lock architecture before writing any code.

---

## 21. Review Findings & Decisions (Locked 2026-09-23)

A principal-engineer-style review of §1–§20, done before any code was written. Each finding below records what was wrong, what it replaces, and the locked decision. The earlier sections carry dated "Revised/Corrected" notes that point here instead of being rewritten.

### Scorecard

| # | Finding | Verdict | Resolution |
|---|---|---|---|
| 1 | No serving-capacity concept; "position 1" hard-codes C = 1 | Correct — conceptual hole | §21.1 |
| 2 | Nothing promotes the next entry when a slot frees | Correct — conceptual hole | §21.1 |
| 3 | §15 ZADDs `join_sequence` before Postgres generates it | Correct | §21.5 |
| 4 | Streams consumer group = wrong semantics for fan-out | Correct | §21.9 |
| 5 | Bump fan-out is O(bump distance) | Correct | §21.7 |
| 6 | Reconnect via `ZRANK` loses RESERVED state + timer | Correct | §21.8 |
| 7 | Crash rebuild is a blind replay; reconciliation one-way | Correct | §21.6 |
| 8 | Version-column story is dead; real race is the banking decision | Correct | §21.4 |
| 9 | Headline reservation feature depends on an unbuilt expiry mechanism | Correct | §21.2 |
| 10 | Hash-bucketed sub-ZSETs break global rank | Correct | §21.12 |
| 11 | "Reject at the edge" is overstated | Correct (wording) | §21.12 |
| 12 | Tenant isolation enforcement unspecified | Correct | §21.10 |
| — | Tomcat "blocking IO" caps sockets far lower | **Overstated** — Tomcat NIO is default; the limit is memory/FDs | §21.12 |
| — | STRICT group ZSET score undefined | **Already answered** by §18 (one entry row per group) | §21.12 |
| — | BUMPED: state or event? | Correct — it's an event | §21.11 |
| **N1** | **Global sequence → sparse scores → "score − N" ≠ "N spots"** (found in self-review, missed by the original review) | **Bug** | §21.3 |

---

### 21.1 Serving capacity + promotion

*Replaces:* "reach position 1 → RESERVED, single slot" (§9, §10, §13, §16).

**Concept.** `waitlist.serving_capacity` (INT, default 1) = **how many entries can hold a checkout window at once**. A restaurant with 4 free tables sets 4; a drop with fast checkout sets e.g. 500. It is *not* inventory. Inventory/stock-out is a documented extension (`inventory_remaining`, decremented on CONFIRM; promotion stops at 0). It is also distinct from the existing `max_capacity` (max queue length).

**Invariant.** `|reserved| ≤ serving_capacity`, and reserved members are always promoted from the head of the queue in score order.

**Redis layout.**
- `waitlist:{id}:queue`: **WAITING entries only**, score = `queue_score`.
- `waitlist:{id}:reserved`: entries holding a slot, **score = expiry epoch ms**.

**`promote.lua` (single atomic script):**
```
-- KEYS[1]=queue KEYS[2]=reserved  ARGV[1]=capacity ARGV[2]=now_ms ARGV[3]=window_ms
promoted = {}
while ZCARD(reserved) < capacity do
    head = ZPOPMIN(queue)            -- {member, score}
    if head is empty then break end
    ZADD(reserved, now_ms + window_ms, head.member)
    append(promoted, head.member)
end
return promoted
```

**Who calls it.** Called after **every** event that can free a slot or add a candidate: join, confirm, cancel, expire, and a capacity config change. The script is **idempotent** (calling it when nothing is free is a no-op), so extra calls are harmless and a missed call is caught by the sweeper, which also calls it every tick. Event-driven for latency, sweeper as a safety net.

**Postgres follow-up.** For each promoted member: `UPDATE waitlist_entry SET state='RESERVED', reserved_at=now, reservation_expires_at=$exp WHERE id=$id AND state='WAITING'`. If this fails after the Lua succeeded, reconciliation rolls it forward (§21.6).

**Does CONFIRMED free a slot?** Yes. A slot is a *checkout window*; confirming ends the window (`ZREM reserved`). What was bought is the tenant's inventory concern.

**Groups.** A STRICT group (one ZSET member `group:{groupId}`) consumes **one** slot, like one table for the whole party. PARTIAL members consume one slot each.

**Concurrency proof (updated).** 100 concurrent reserve/promote attempts with capacity C → exactly C RESERVED. The "broken" version does `ZCARD` then `ZADD` from Java and over-admits.

**Interview framing:** "Position 1 isn't 'your turn'. Having a slot is. Capacity is config; promotion is one idempotent atomic script fired on every slot-freeing event, backed by a sweeper."

---

### 21.2 Reservation expiry — built MVP

*Replaces:* the gap between "reservation state machine = building" and "expiry mechanism = documented only" (§12.4).

- **Sweeper:** Spring `@Scheduled(fixedDelay = 1s)` per active waitlist: `ZRANGEBYSCORE reserved -inf now LIMIT 0 100`.
- **`expire.lua`:** `ZREM` a member **only if its score is still ≤ now**. Returns 1 if it removed it.
- **`confirm.lua`:** `ZREM` a member **only if its score is > now**. Returns 0 if it has already expired → `409 Conflict`.
- **Confirm vs. expire race:** whichever script `ZREM`s first wins, and Redis's atomicity is the arbiter. The Postgres writes are conditional (`... WHERE state='RESERVED'`), so the loser's write is a no-op.
- **Multiple Core instances:** the `ZREM` return value *is* the single-consumer guarantee. Only the instance that got `1` processes that expiry. **No distributed lock needed**, which resolves §12.4 row 2.
- **Why this already scales better than "polling all RESERVED rows":** the reserved ZSET is indexed by expiry, so each tick costs O(log N + expired), not O(all reservations). Keyspace notifications remain the documented push-based upgrade.
- After expiring, call `promote`.

---

### 21.3 Referral bump — rank-based, explicit score (new finding N1)

*Replaces:* `ZINCRBY -N` (§6) and the implicit `score = join_sequence − total_bumps_applied` (§5 Decision 1, §18).

**The bug.** `join_sequence` is a **global** sequence, so within one waitlist consecutive entries can be thousands apart (other waitlists' joins interleave). Subtracting `bump_amount` often moves a user **zero** positions. The Alice seed data only works because it is dense. The feature promises "move up N spots"; score arithmetic can't deliver that on sparse scores.

**`bump.lua`:**
```
-- KEYS[1]=queue  ARGV[1]=member ARGV[2]=N
r = ZRANK(queue, member)
if r == nil then return {"BANK"} end          -- not WAITING: bank the credit (§21.4)
target = max(0, r - N)
if target == r then return {"NOOP"} end        -- already at the head
if target == 0 then
    newScore = score_at_rank(0) - 1
else
    a = score_at_rank(target - 1); b = score_at_rank(target)
    newScore = (a + b) / 2
    if newScore == a or newScore == b then return {"PRECISION"} end
end
ZADD(queue, newScore, member)
return {"APPLIED", newScore, target}
```
- Still O(log N): `ZRANK`, `ZRANGE` by rank, `ZADD`.
- **Ties are eliminated.** The midpoint is strictly between the two neighbours, so no lexicographic tie-breaking by userId.
- **Precision:** doubles allow ~50 midpoint halvings between two adjacent integers. On `PRECISION`, bank the credit and emit a metric. A renormalisation job (rewrite the waitlist's scores as 1..N) is documented, not built.

**Persistence.** New column `waitlist_entry.queue_score DOUBLE PRECISION NOT NULL`, initialised to `join_sequence` and rewritten by each applied bump. `total_bumps_applied` remains as an audit counter only. Crash recovery replays `queue_score`.

---

### 21.4 Referral credit race — the banking decision

*Replaces:* optimistic locking/version column (§10).

**The real race.** It isn't the counter (atomic `+N` already handles that). It is the check-then-act: "is the user still waiting? → apply, else bank". Two concurrent referrals, or a referral racing a promotion, could both see "waiting" and one would bump a user who has just been promoted.

**Fix.** The decision is made inside `bump.lua` (§21.3), because queue membership *is* the WAITING check and it's atomic with the `ZADD`. Postgres then records the outcome with plain atomic statements:
- **APPLIED:** `UPDATE waitlist_entry SET queue_score=$s, total_bumps_applied = total_bumps_applied + $n WHERE id=$id AND state='WAITING'`, then `UPDATE referral_credit SET total_credits_earned += $n, total_credits_applied += $n`.
- **BANK / PRECISION:** `UPDATE referral_credit SET pending_credits += $n, total_credits_earned += $n`.

**Referral flow order:** fraud check → `INSERT referral` (its `UNIQUE (waitlist_id, referrer_id, referee_id)` doubles as the idempotency key for the credit, so a retried referral can't double-credit) → `bump.lua` → credit ledger update.

**Spending banked credits.** Credits are spent on a later join to the same waitlist (e.g. after EXPIRED/CANCELLED). Right after the join's `ZADD`, run `bump.lua` with the pending amount, then `UPDATE referral_credit SET pending_credits = pending_credits - $n WHERE ... AND pending_credits >= $n` (conditional, so two concurrent re-joins can't both spend it).

---

### 21.5 Join write ordering — Postgres first

*Replaces:* §15 Step 2 ("Redis first").

`INSERT waitlist_entry ... RETURNING id, join_sequence` → `ZADD queue join_sequence member` → spend banked credits (§21.4) → `promote` → publish event.

**Why this order is safer:** the durable row always exists before the live state. A crash between the two writes leaves a Postgres WAITING row with no ZSET member, and reconciliation adds it (§21.6). The reverse order could leave a live queue member with no durable record, which is harder to justify and impossible here anyway, since the score comes from Postgres.

---

### 21.6 Crash recovery & reconciliation — state-aware, bidirectional

*Replaces:* "replay every entry's `join_sequence`" (§5, §15, §18) and the one-directional reconciliation job.

**Rebuild (Redis lost):**
- `queue` ← `WAITING` rows, score = `queue_score`.
- `reserved` ← `RESERVED` rows, score = `reservation_expires_at`. Ones that expired during the outage are swept on the next tick. They are **not** demoted back to WAITING.
- `CONFIRMED` / `CANCELLED` / `EXPIRED` are skipped.
- Then run `promote` to refill any free slots.

**Reconciliation job** (every ~60 s per active waitlist, only for entries with `updated_at < now − 30s` so it never races the hot path):

| Mismatch | Cause | Action |
|---|---|---|
| Postgres WAITING/RESERVED, missing from Redis | ZADD failed after INSERT | Add to the correct ZSET |
| Redis member, no active Postgres row | Cancel's ZREM failed | Remove from Redis |
| In `reserved` ZSET, Postgres row still WAITING | Post-`promote` Postgres update failed | Roll Postgres forward to RESERVED |

**Authority rule:** Postgres is authoritative for **existence** (is there an active entry?). Redis is authoritative for **ordering and slot-allocation decisions** already made atomically by Lua. Reconciliation rolls those decisions forward into Postgres; it never reverses them.

---

### 21.7 Real-time fan-out model

*Refines:* §8 ("on any position change, publish") and §15 Step 3.

- **Positions are computed on read (`ZRANK`), never pushed per shifted user by Core.**
- Core publishes small events to `waitlist:{id}:events`: `{type, userId}` where type ∈ `JOINED, BUMPED, RESERVED, CONFIRMED, EXPIRED, CANCELLED`.
- **Gateway:** (a) pushes directly-addressed events immediately to that user's socket, e.g. `RESERVED` with `expiresAt`; (b) marks the waitlist **dirty**. Every ~1 s, for each dirty waitlist, it pipelines `ZRANK` for its **locally connected** users on that waitlist and pushes only the positions that changed.
- **Cost:** O(local connections on dirty waitlists × log N) per tick, independent of bump distance and coalesced under a referral storm. At extreme scale: push only when a user crosses a position bucket (every 100 places), or show approximate positions.
- **Robustness bonus:** a dropped event only loses a dirty flag, which self-heals on the next event or a periodic full refresh. This makes Pub/Sub's fire-and-forget much less dangerous.

---

### 21.8 Reconnect resync

*Replaces:* §15 Step 5.

Pipeline `ZSCORE queue m` + `ZSCORE reserved m`:
- in `queue` → `WAITING`, position = `ZRANK + 1`
- in `reserved` → `RESERVED`, `expiresAt` = the score, so the countdown survives reconnects
- neither → Postgres lookup of the latest entry → `CONFIRMED` / `EXPIRED` / `CANCELLED` / not joined

This is one Redis round trip, and Postgres is touched only for the terminal case.

---

### 21.9 Redis Streams / Kafka upgrade semantics

*Corrects:* §8 resolution and §12.2.

Fan-out needs **broadcast**: every gateway instance must see every event for waitlists it serves. A **shared** consumer group load-balances each event to one instance, which usually isn't the one holding the user's socket, so the event is dropped. The upgrade therefore **introduces** loss if done naively. Correct topology: each gateway reads the whole stream with **its own offset** (plain `XREAD` from its last ID, or one consumer group *per instance*). In Kafka terms, each gateway is its own consumer group. Per-instance offsets still give replay on restart.

**Interview line:** "'Add Kafka' isn't a free upgrade. Consumer-group semantics are load-balancing, and fan-out needs broadcast."

---

### 21.10 Tenant isolation

*New* (previously unspecified).

- **Identity:** end-user JWTs carry a `tenant_id` claim (tokens are issued per tenant). Tenant admin APIs authenticate with `tenant.api_key`.
- **Single enforcement point:** a `WaitlistAccessGuard` (a Spring `HandlerMethodArgumentResolver` producing a `TenantScopedWaitlist`) resolves `waitlistId` → `waitlist` row (cached) and asserts `waitlist.tenant_id == caller.tenant_id`. On mismatch it returns **404, not 403**, so the existence of other tenants' waitlists isn't leaked. Services only accept `TenantScopedWaitlist`, never a raw id, so forgetting the check fails at compile time.
- **Redis:** keys are per `waitlistId` (UUID), so once the guard passes there is no cross-tenant path.
- **Cross-waitlist queries** ("all my queues") join `waitlist` and filter on `tenant_id`.
- **Documented upgrade:** Postgres Row-Level Security with `SET LOCAL app.tenant_id` per transaction, as defence in depth.

---

### 21.11 State model — BUMPED becomes an event

`BUMPED` never persists (it reverts in the same transaction), so it is removed from the `waitlist_entry.state` CHECK and exists only as a fan-out event type used for the frontend animation.

**Five states:** `WAITING`, `RESERVED`, `CONFIRMED`, `EXPIRED`, `CANCELLED`.
**Transitions:** `WAITING → RESERVED` (promoted) · `RESERVED → CONFIRMED` · `RESERVED → EXPIRED` · `WAITING → CANCELLED` · `RESERVED → CANCELLED` (declines; frees the slot → `promote`).

---

### 21.12 Wording & framing corrections

- **Rate limiting (§15 Step 1):** "rejected before Redis/Postgres", not "at the edge". It still uses Core threads. True edge shedding is at the LB, or comes from the Admission extraction.
- **Hot key (§16):** hash buckets break global rank. Use **score-range sharding** (global rank = local rank + sum of earlier bucket sizes) or **approximate positions** at extreme scale.
- **Sockets per JVM (§16):** 50–100K assumes a non-blocking connector (Tomcat NIO, which is the default, or Netty) plus FD/heap tuning; the bound is memory per session. *The original review's claim that default Tomcat is blocking was overstated.*
- **STRICT group score:** already defined by §18. A STRICT group has one `waitlist_entry` row; ZSET member `group:{groupId}`; score = that row's `queue_score` (initially the creator's `join_sequence` at group creation). It occupies one serving slot (§21.1).

---

### 21.13 Pending `init.sql` changes (applied in Brick 1 as Flyway `V1__schema.sql`, §22)

- `waitlist`: add `serving_capacity INTEGER NOT NULL DEFAULT 1 CHECK (serving_capacity > 0)`. Clarify the `max_capacity` comment as "max queue length".
- `waitlist_entry`: add `queue_score DOUBLE PRECISION NOT NULL` (the app sets it to `join_sequence` on insert). Remove `'BUMPED'` from the state CHECK. Add an index `(waitlist_id, state)` for recovery and reconciliation scans.
- Update the `total_bumps_applied` / score comments to match §21.3.
- Seed data: set `queue_score` explicitly, seed Alice's lead using the rank model, and add one `RESERVED` entry with a future `reservation_expires_at` so the countdown is visible on first load.


---

## 22. Implementation Log

One entry per brick: what was built, the implementation-level decisions (the ones too small for §5–§21 but still worth defending), and how it was verified.

### Brick 1 — Foundations (2026-09-24)

**Built:** repo layout; `core-queue-service` and `websocket-gateway` skeletons (Spring Boot 3.5, Java 21, Maven); schema as Flyway migrations with every §21.13 change applied; demo seed rewritten for the rank/capacity model; React + Vite + TS dashboard shell with a live system-status panel; `docker compose up --build` for the whole stack.

**Repo layout**
```
core-queue-service/   com.waas.core.{common,tenant,admission,queue,reservation,referral,group}
websocket-gateway/    com.waas.gateway.{config,fanout}
frontend/             React + Vite + TS, nginx in compose
docker-compose.yml    postgres, redis, core, gateway, frontend
```
Each Core package has a `package-info.java` stating what it owns and which direction its dependencies may point (queue ← reservation ← group; queue ← referral; nothing → referral). This is the "module boundaries map 1:1 to microservices" claim from §13 made checkable.

**Decisions**

| Decision | Why | Alternative rejected |
|---|---|---|
| **Flyway migrations replace the Docker `init.sql`** (`db/migration/V1__schema.sql`) | The Docker init script runs only on a fresh volume, so schema changes silently never apply to an existing DB. Flyway versions the schema with the app and runs identically in tests (Testcontainers) and compose. | Docker entrypoint script (the old approach). |
| **Demo seed is a repeatable migration** (`db/seed/R__demo_seed.sql`), loaded only by the `demo` profile | `R__` runs after all versioned migrations, so future `V2`, `V3`… never collide with it. `ON CONFLICT DO NOTHING` makes it idempotent. Production never sees demo rows. | Seed as `V2__seed` (would block every later migration numbering and ship to prod). |
| **Plain SQL via `JdbcClient`, no JPA** | The design depends on precise SQL: `INSERT … RETURNING`, conditional `UPDATE … WHERE state = 'WAITING'`, atomic increments. JPA hides exactly the statements the concurrency story rests on (dirty checking, flush ordering). | Spring Data JPA / Hibernate. |
| **Virtual threads on** (`spring.threads.virtual.enabled`) | A join blocks on Postgres + Redis I/O. Virtual threads make thread-per-request cheap under a burst; the limiter becomes the Hikari pool (20), which is the resource that *should* be the bottleneck. | Reactive WebFlux (a different programming model everywhere, for a gain virtual threads now give almost for free). |
| **STOMP simple (in-memory) broker in the gateway, no SockJS** | Every gateway instance receives all events from Redis (§21.9 broadcast), so each only needs to deliver to its own sockets, which is what the simple broker does. SockJS would add a second transport for browsers that no longer exist. | STOMP broker relay to RabbitMQ (a second event backbone duplicating Redis). |
| **Same-origin routing: the SPA only uses relative URLs** | Vite's dev proxy and nginx in compose expose identical routes (`/api` → Core, `/ws` → Gateway, `/core/actuator`, `/gateway/actuator`), so there is no CORS config and dev behaves like prod. | CORS on both services + absolute URLs per environment. |
| **Redis with AOF in compose** | A Redis restart keeps live queue state. Postgres rebuild (§21.6) is for losing the volume, not for every restart. Makes §12.8's RPO concrete. | No persistence (every restart forces a rebuild). |
| **Images build inside Docker (multi-stage)** | `docker compose up --build` needs only Docker on the host, with no local Java/Maven/Node. Tests are skipped in the image build because Testcontainers needs a Docker daemon; they run in CI. | Requiring a local JDK + Maven. |

**Schema changes vs. the old `init.sql`** (all from §21.13): `serving_capacity`; `queue_score DOUBLE PRECISION`; `BUMPED` removed from the state CHECK; `referral_credit.version` dropped; `(waitlist_id, state)` index replaces the separate waitlist and reserved-expiry indexes (expiry is now indexed by the Redis reserved ZSET, §21.2); new `chk_credit_accounting` (applied + pending ≤ earned); `chk_reserved_needs_expiry` now also requires `reserved_at`.

**Seed story (rank model):** AirMax Drop, capacity 1, bump 2. Frank holds the one slot (RESERVED, 10-min countdown). Alice joined 6th; Grace, Heidi, and Ivan joined via her link. Bumps take her rank 4 → 2 (score 3.5) → 0 (score 1.0); the third credit is **banked** because she's already at the head. Ledger: earned 6, applied 4, pending 2. Credits applied now means "spots actually gained", with leftovers banked (refines §21.3/§21.4).

**Verified:** V1 schema + seed applied to a real PostgreSQL 16 (seed applied twice to prove idempotency, expected queue order checked). Frontend type-checks, lints, and builds; the layout was screenshot-checked in light/dark/mobile. `docker compose config` validates. **Not yet verified in the build sandbox:** Java compilation and container builds (Maven Central and Docker Hub are blocked there), so the first `docker compose up --build` on a dev machine is the compile check. `InfrastructureTest` asserts schema, seed order, the BUMPED rejection, and Redis reachability.

**Carried into Brick 2:** Redis is empty on first boot. Brick 2's startup rebuild (§21.6) loads WAITING/RESERVED rows into the ZSETs, which is also how the seed reaches Redis.


### Brick 2 — Queue module (2026-09-24)

**Built:** join, position read, cancel (WAITING only), dashboard snapshot, startup rebuild of Redis from Postgres, queue events on Pub/Sub, minimal user creation. The dashboard now shows the live queue: waitlist tabs, serving slots with countdowns, the ranked waiting list with raw ZSET scores, join/burst controls, and a per-user "your people" panel.

**API**

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/waitlists` | Active waitlists (config only) |
| `POST` | `/api/users` | `{name, email?}`, demo users, no password until Brick 7 |
| `POST` | `/api/waitlists/{id}/entries` | Header `X-User-Id`. **201** new entry, **200** idempotent repeat. Returns position. |
| `GET` | `/api/waitlists/{id}/entries/{entryId}` | Position / state (locate.lua, Postgres fallback) |
| `DELETE` | `/api/waitlists/{id}/entries/{entryId}` | WAITING → CANCELLED, idempotent |
| `GET` | `/api/waitlists/{id}/queue?limit=50` | Reserved slots + head of queue, one atomic read |

Errors are RFC 9457 Problem Details with a stable `code` (`WAITLIST_NOT_FOUND`, `WAITLIST_FULL`, `NOT_WAITING`, `DEPENDENCY_UNAVAILABLE`, …).

**Decisions**

| Decision | Why | Alternative rejected |
|---|---|---|
| **ZSET member = `waitlist_entry.id`**, not `userId` | A re-join after CANCELLED/EXPIRED gets a new id, so a stale member can never be mistaken for the new entry. A STRICT group is already one entry row, so it needs no special `group:{id}` member format (settles the §14/§18 open point). Clients resync with the `entryId` they got from the join. | `userId` as member (needs a separate format for groups and is ambiguous across re-joins). |
| **Keys are hash-tagged now:** `waitlist:{<id>}:queue` | All of a waitlist's keys land on one Redis Cluster slot, which multi-key Lua scripts require. Free on a single node, so there's no migration later. | Plain `waitlist:<id>:queue` (forces a key rename before clustering). |
| **Lua scripts as `.lua` files**, run with EVALSHA | Readable and reviewable, and each is tested on its own with `redis-cli --eval`. `join.lua` = ZADD NX + ZRANK + ZCARD. `locate.lua` / `snapshot.lua` are atomic multi-key reads. Scores come back as strings, because Redis turns Lua numbers into integers and would truncate 3.5 → 3. | Inline Java strings; MULTI/EXEC (can't branch on a read). |
| **Join is idempotent per (user, waitlist)** | The partial unique index *is* the dedup key: a repeat returns the existing entry (200), and a concurrent duplicate that loses the race gets the winner's entry. This covers the most important retry case before Brick 7's general idempotency keys. | 409 on duplicate (makes client retries unsafe). |
| **`ZADD NX` makes retry = repair** | If Postgres succeeded but the ZADD failed, the entry is durable but not live. Retrying the join re-adds it with its stored score. NX means a member already there keeps its (possibly bumped) score. | Background repair only. |
| **Join returns the position synchronously (201)**, not 202 | `ZRANK` is O(log N), so there's nothing to defer. The 202 + WebSocket confirmation sketched in §15 becomes right only when a Kafka admission buffer makes joining async. | 202 now (adds latency and a moving part for no gain). |
| **`max_capacity` is a soft cap** | ZCARD-then-insert isn't atomic, so concurrent joins can overshoot by the number of in-flight joins. A hard cap would need the Postgres insert and the Redis add to be one atomic unit, and they're two stores. | Hard cap via Lua (then a rejected join leaves an orphan Postgres row to clean up). |
| **No `@Transactional`** on queue writes | Each write is one SQL statement, and no DB transaction can span Redis anyway. Correctness comes from ordering (Postgres first) + conditional updates + idempotent repair. | Wrapping in a transaction that implies atomicity it doesn't have. |
| **Events are tiny and fire-and-forget** | `{type, waitlistId, entryId, userId, at}`, never positions (§21.7). A publish failure is logged, not propagated: the state is already durable, and a lost event only delays a UI refresh. | Failing the join on publish failure; publishing positions. |
| **Startup rebuild in `SmartInitializingSingleton`** | Runs after all beans exist but before Tomcat accepts requests, so no request on this instance sees a half-built queue. It only rebuilds a missing key (an existing key survived via AOF), stages the data off to the side, then swaps it in (`rebuild_swap.lua`: RENAME, or MIN-merge if a join landed meanwhile). A SET NX EX lock stops two instances from rebuilding the same waitlist. | `ApplicationReadyEvent` (traffic may already be flowing); rebuilding straight into the live key (readers see a partial queue). |
| **One choke point for waitlist access:** `WaitlistService.require()` | Brick 7's tenant guard (§21.10) becomes a change to this one method. | Each module querying `waitlist` directly. |
| **Dashboard polls every 1 s** (visible tabs only) | Scaffolding until Brick 5's gateway push. One snapshot is O(log N + page). | Building fan-out before the queue exists. |
| **`X-User-Id` header** stands in for the JWT subject | Brick 7 swaps the source; service signatures don't change. | Auth before there's anything to protect. |

**Known gap (documented, fixed in Brick 3):** the rebuild can re-add an entry cancelled between its Postgres read and the swap. The reconciliation job removes it.

**Verified:** all four Lua scripts against Redis 7 (fresh join, retry keeping a bumped score, locate for WAITING/RESERVED/ABSENT, snapshot, rebuild RENAMED/MERGED/EMPTY). The join INSERT, duplicate rejection, conditional cancel, and the rebuild query's index use against PostgreSQL 16. The dashboard against a mock API (light/dark, 390 px with no horizontal scroll, join + burst flows). **On your machine:** `mvn test` runs `QueueFlowTest`: seed rebuilt into Redis, idempotent join, 40 concurrent joins → 40 distinct contiguous positions, cancel + re-join, rebuild restores exact order after Redis loses the keys, retry repairs a missing member.


### Brick 3 — Reservation (2026-09-24)

**Built:** serving capacity end to end. Joins into a free slot are reserved immediately. Confirm and decline (holder only). A 1 s expiry sweeper. Capacity/window config (`PATCH /api/waitlists/{id}`) with instant promotion on increase. Bidirectional Postgres↔Redis reconciliation every 30 s. Confirmed/expired counters. The dashboard gets live slot countdowns with Confirm/Release, a config card, a per-user Confirm, and the counters.

**New API:** `POST /api/waitlists/{id}/entries/{entryId}/confirm` and `/decline` (header `X-User-Id` must be the entry's owner, else 404), and `PATCH /api/waitlists/{id}` `{servingCapacity?, reservationWindowSeconds?}`.

**Scripts** (all verified against Redis 7):
- `promote.lua`: `while ZCARD(reserved) < C: ZPOPMIN queue → ZADD reserved (now+window)`. Returns the promoted ids.
- `confirm.lua`: ZREM only if expiry > now. Returns 1 (confirmed) / −1 (lapsed) / 0 (absent).
- `expire_due.lua`: `ZRANGE reserved -inf now BYSCORE LIMIT` + `ZREM` in one step.
- `snapshot.lua` now also returns `waitlist:{id}:stats` (HMGET confirmed, expired).

**The race, measured:** 100 WAITING members, capacity 3, **200 concurrent callers**. The naive version (ZCARD, then ZPOPMIN/ZADD as separate commands) **reserved 60 entries**. `promote.lua` reserved **exactly 3**, in queue order. This is the live demo for §16's concurrency proof.

**Decisions**

| Decision | Why | Alternative rejected |
|---|---|---|
| **Reservation writes go Redis first, then Postgres** (the opposite of join) | Join *creates a record*, so the durable store goes first. Reservation *allocates a scarce slot / races a clock*, and that decision must be atomic, which happens in Lua. Postgres records the outcome with conditional UPDATEs. A lost Postgres write is rolled forward by the reconciler (§21.6 authority rule). | Postgres first (you can't make "is a slot free?" atomic across two stores without a distributed transaction). |
| **Confirm vs. expire are disjoint by construction** | `confirm.lua` removes only if expiry > now; `expire_due.lua` removes only if expiry ≤ now. Both are atomic, so exactly one can win for any member. No lock, no retry loop. | Row lock in Postgres (Redis is where the slot lives). |
| **Confirm retry = repair** | If Redis says ABSENT but Postgres says RESERVED with a future expiry, our own earlier attempt removed it and died before recording. Finish the Postgres write. | Failing the retry (the user loses a slot they legitimately confirmed). |
| **The sweeper is safe to run on every instance** | `expire_due.lua` hands each lapsed member to exactly one caller; `promote.lua` can't overfill. No leader election or distributed lock needed. Each tick is O(log N + work) because the reserved set is indexed by expiry. | Leader-elected single sweeper (a new failure mode for no gain). |
| **Join → promote via an in-process event** (`EntryJoined`) | Reservation depends on Queue, never the reverse. A synchronous Spring `@EventListener` keeps that direction with no async hop, so the join response already says RESERVED when a slot was free. A promotion failure is logged, not propagated, and the next sweeper tick (≤ 1 s) catches it. | Queue calling Reservation directly (cyclic module dependency). |
| **Capacity decrease never evicts** | Holders keep their windows and slots drain naturally. Taking a checkout window away mid-purchase is worse than temporarily running over C. | Evicting the newest holders. |
| **Ownership check returns 404, not 403** | Confirming someone else's entry must not reveal that it exists. | 403. |
| **Stats are Redis counters (HINCRBY)** | O(1) to read on every 1 s dashboard poll. They're derived data: losing them loses only the counters. | `COUNT(*) GROUP BY state` in Postgres on every poll. |
| **All time decisions use the app clock** (`Clock` bean) | Expiry scores, `now` for confirm/expire, and `reserved_at` all come from one clock. Postgres `NOW()` is used only for `updated_at` bookkeeping. On Docker Desktop, the container clock can drift seconds from the host's. | Mixing DB and app clocks in comparisons. |
| **Reconciler: grace window + Redis-wins for slots** | Skips Postgres rows touched in the last 30 s (in-flight writes aren't gaps). Repairs: lost join ZADD → re-add; slot missing from Redis → restore if still in its window, else EXPIRE; lost cancel ZREM → remove; lost promote write → roll Postgres forward to RESERVED; leaked slot (row no longer RESERVED) → free it. One instance per waitlist via SET NX EX. Also closes Brick 2's rebuild gap. | Postgres-wins everywhere (it would undo slot decisions users were already told about). |
| **Background jobs are switchable** (`waas.scheduling.enabled`) | Tests turn them off and call `sweep()` / `reconcile()` directly: deterministic, no background tick racing assertions. | `Thread.sleep` in tests waiting for the scheduler. |

**Test fix:** the Maven Surefire plugin now passes `-Dapi.version=1.44`. Docker Engine 29 rejects Testcontainers 1.21.x's default (older) Docker API version with a 400, which surfaces as "Could not find a valid Docker environment". The Brick 2 concurrency test was also corrected: positions returned *during* concurrent joins can legitimately repeat ("rank at that instant"). The real invariant is that the final queue is contiguous 1..n in join_sequence order.

**Verified:** the three new scripts + capacity under 200 concurrent callers against Redis 7. All Java passes a `javac` syntax check (Spring isn't resolvable in the sandbox). The dashboard flow runs against a mock API: expiry → promotion, capacity raise → instant promotion, confirm → next promoted. **On your machine:** `ReservationFlowTest` (immediate reservation, 60 concurrent joins → exactly 3 RESERVED in both stores, confirm/decline hand-off, holder-only confirm, lapsed confirm → 409 then the sweeper promotes, capacity raise, reconciler both directions + roll-forward) plus the updated `QueueFlowTest`.


### Brick 4 — Referral (2026-09-24)

**Built:** referral links (`POST /entries?ref=<userId>`), a rank-based bump that moves the referrer up exactly N places (or as many as exist), apply-vs-bank with a credit ledger, banked credits spent automatically on re-join, a fraud velocity limit with an audit trail, and referral reads. The dashboard gets a ↑ "refer a friend" button on every waiting row and in "Your people", animated reordering (FLIP) with a flash on the row that moved up, a per-person ledger (`credits · used · banked · blocked`), and a live referral feed.

**New API:** `GET /api/waitlists/{id}/users/{userId}/credits` → `{earned, applied, pending, credited, rejected}`, and `GET /api/waitlists/{id}/referrals?limit=` → recent attempts, credited and rejected. There's deliberately no "create referral" endpoint: a referral *is* a join through a link, so it can't be forged without a real join.

**Schema:** `V2__referral_status.sql` adds `referral.status` (CREDITED / REJECTED) + `rejection_reason` (required when REJECTED), plus an index for the feed. It's the first real Flyway migration on top of V1, and it applies to existing databases in place.

**`bump.lua`** (verified against Redis 7): `ZRANK` → target = max(0, rank − N) → new score = midpoint of the two neighbours around the target (or head − 1) → ZADD → read the score back with ZSCORE. Returns `BANK` (not waiting), `APPLIED score rank moved`, or `PRECISION`.
- **Measured precision headroom:** squeezing repeatedly into one integer gap gives **52 successful halvings**. The 53rd is refused (`PRECISION`) and its credits banked. Every score stays strictly ordered, and scores round-trip at full 17-digit precision (`1.0000000000000002`).
- The score is read back with ZSCORE (Redis formats it at 17 significant digits) rather than taken from the Lua `tostring`, which uses 14 and would silently drift from what Redis actually stores.

**Decisions**

| Decision | Why | Alternative rejected |
|---|---|---|
| **Credits applied = spots actually gained; the rest is banked** | "Move up 2" when you're 2nd means one spot is real and one isn't. Banking the remainder keeps the ledger honest (`applied + pending ≤ earned`, enforced by a DB CHECK) and nothing is silently lost. | Crediting the full N (inflated audit), discarding the remainder (the user loses what they earned). |
| **The apply-vs-bank decision lives in `bump.lua`** | "Is the referrer still WAITING?" *is* "is the member in the queue ZSET?", and bump.lua answers it and moves in one atomic step. A referral racing a promotion can't bump someone who was just reserved (§21.4). | Checking state in Postgres, then bumping in Redis (a check-then-act gap). |
| **The referral row is the idempotency key** | `INSERT … ON CONFLICT DO NOTHING` on UNIQUE (waitlist, referrer, referee): a retried join, a double-click, or a cancel-and-rejoin through the same link can't pay out twice. And only a *new* entry announces a referral, so an idempotent join repeat emits nothing. | An application-level "already credited?" check (racy). |
| **Rejected referrals are recorded, not dropped** | Abuse needs a trail: who tripped the limit, when, for whom. `status = REJECTED, rejection_reason = VELOCITY_LIMIT`. | Silently ignoring them (no forensics). |
| **Velocity counter: `SET key 0 EX window NX`, then `INCR`** | The key gets its TTL before it's ever incremented. The textbook `INCR` then `EXPIRE` can crash in between and leave a counter that never expires, locking the user out forever. A fixed window is simple; the sliding-window log (ZSET of timestamps) is the documented upgrade for burst-at-the-boundary accuracy. | INCR + EXPIRE; a Lua script (unnecessary here). |
| **Referral runs as an `EntryJoined` listener; failures are logged, not propagated** | The referee's join already succeeded. A self-referral, a forged `?ref=` (unknown user), a velocity rejection, or a Redis error on the *referrer's* side must never fail *their* join. | Failing the join (one person's bookkeeping breaks another's action). |
| **Banked credits are spent on re-join** | Credits earned while reserved/confirmed/absent are applied the moment the user is back in the queue: `bump.lua` with the pending amount, then a conditional `pending = pending − moved WHERE pending ≥ moved`. | Expiring banked credits (unfair); spending them by hand (friction). |
| **Bumped score is persisted in `queue_score`** | Crash recovery replays the stored score, so a rebuild reproduces the bumped order exactly (tested). **Known gap:** if that Postgres write is lost, Redis keeps the new order but a later rebuild restores the old score. The reconciler checks membership, not scores. Closing that fully needs an outbox (§12.3); documented, not built. | Recomputing scores from a bump log on rebuild (a second source of truth). |

**Dashboard animation:** reordering uses FLIP (measure positions, invert with a transform, play to zero) on the table rows. The "moved up" flash runs through the Web Animations API, not a CSS class, because React owns `className` and the next 1 s poll re-render would wipe an added class mid-flash. Respects `prefers-reduced-motion`.

**Verified:** `bump.lua` (exact-N move between sparse scores, partial move at the head, head no-op, BANK when absent, 52-halving precision limit with strict ordering) against Redis 7. V2 migration on top of V1 + seed, ledger upsert accumulation, conditional spend, and the REJECTED-needs-reason CHECK against PostgreSQL 16. Java passes a `javac` syntax check. The dashboard runs against a mock: refer → animated climb from #9 to #1, ledger `10 · used 8 · banked 2 · 1 blocked`, velocity block in the feed, light/dark, 390 px. **On your machine:** `ReferralFlowTest` (exact-N move, head banking, reserved-referrer banking, spend-on-rejoin, no double pay across repeat/cancel/rejoin, self + forged referrer ignored while the joins succeed, velocity 5 credited / 2 rejected, bumped order survives a Redis rebuild).


### Brick 5 — Live fan-out (2026-09-24)

**Built:** the gateway now does the §21.7 fan-out. It PSUBSCRIBEs to every waitlist's event channel, tracks which waitlists and entries are watched *on this instance*, recomputes on a 500 ms coalescing tick, pushes only what changed, answers one-shot current-state subscriptions, and exposes `/stats`. The dashboard runs on push: no REST polling while the socket is up, automatic fallback to 1 s polling when it's down, and automatic resync on reconnect. Core is unchanged: it already published the events in Bricks 2–4.

**STOMP contract**

| Destination | Payload | Who |
|---|---|---|
| `/topic/waitlists/{w}/queue` | Core's queue snapshot (relayed JSON) | dashboard viewers |
| `/topic/waitlists/{w}/entries/{e}` | `{entryId, waitlistId, state, position, queueSize, expiresAt}` | one person's view |
| `/app/…` (same paths) | one-shot current state, sent only to the subscriber | on subscribe and every reconnect |

**How an event becomes pushes**
1. Core publishes `{type, waitlistId, entryId, …}` on `waitlist:{w}:events`. **Every** gateway instance receives it (broadcast, §21.9).
2. The gateway marks `w` dirty. If the event is *personal* (RESERVED / BUMPED / CONFIRMED / EXPIRED / CANCELLED) and that entry is watched here, it pushes to that person immediately.
3. On each tick, for each dirty waitlist: **one pipelined Redis read** (ZCARD + ZRANK/ZSCORE per locally watched entry) → push each entry whose JSON changed. If anyone here watches the whole queue: **one** Core snapshot fetch → broadcast to all of them.
4. Every 5 s, everything watched is marked dirty: the self-heal for fire-and-forget Pub/Sub.

**Decisions**

| Decision | Why | Alternative rejected |
|---|---|---|
| **Dirty-marking + tick instead of per-event recompute** | 1,000 bumps in one tick cost exactly one recompute per waitlist. Cost is O(local watchers × log N) per tick, independent of event rate *and* of how far anyone jumped (the §21.7 fix to review finding #5). | Recompute per event (a referral storm multiplies work); pushing to every shifted user (O(bump distance) messages). |
| **Per-instance subscription registry** (from STOMP session events) | Every instance hears every event, but does work only for its own sockets. A waitlist nobody on this node is watching costs this node zero pushes and zero reads (tested). UNSUBSCRIBE frames carry no destination, so subscriptions are keyed by (session, subscription id), and a disconnect clears the session. | Global "who watches what" in Redis (a cross-node write on every subscribe). |
| **Two read paths: Redis for positions, Core for the dashboard** | Positions need only the sorted sets, so the gateway reads Redis directly (no Core round trip, no Postgres). The dashboard needs names (Postgres), so the gateway fetches *one* Core snapshot per waitlist per tick and broadcasts it. N viewers cost 1 read. Relayed as raw JSON: Core can change the shape without a gateway redeploy. | Every viewer polling Core (N reads); the gateway querying Postgres (a second owner of the read model). |
| **`@SubscribeMapping` for initial state** | The reply goes straight to the subscriber, not through the broker. That avoids the race where a push to a `/topic` the instant SUBSCRIBE arrives beats the broker registering the subscription. | Pushing to the topic on SessionSubscribeEvent (lossy race). |
| **The client re-subscribes to everything on every (re)connect**, including the `/app` one-shots | stompjs drops subscriptions with the socket. The provider stores *desired* subscriptions, so reconnecting automatically re-fetches current state: the §21.8 resync in one round trip, no event replay, no Pub/Sub durability needed. | Replaying missed events (requires Streams + offsets). |
| **Push only on change** (last JSON sent per entry) | Most ticks change nothing for most people; dedupe keeps sockets quiet (tested: an unchanged position is never resent). | Pushing every tick. |
| **Graceful degradation in the UI** | Socket down → the dashboard polls REST at 1 s and says so in the header badge; socket back → polling stops. The system stays usable with the gateway dead. | A frozen UI when the gateway is down. |
| **Ordered delivery per session** (`setPreservePublishOrder(true)`) | A client never sees an older position arrive after the newer one that replaced it. | Default unordered publish via the thread pool. |
| **Slow read models stay polled** (credit ledger 3 s, referral feed 2 s) | They change on user actions, not per tick; pushing them would mean new event types for little gain. | Pushing everything. |

**Scale notes:** the tick cost per instance is bounded by its own sockets, so adding gateway instances divides the work. Hot-waitlist ceiling: if one waitlist has ~50K watchers on one node, the per-tick pipelined read is ~50K ZRANKs (≈ tens of ms in Redis). The next step would be a per-node *position cache*: read the queue once with ZRANGE and compute ranks locally, or push position *buckets* ("~2,300th") and only exact positions near the front.

**Verified:** the PSUBSCRIBE pattern against Redis 7 (braces are literal: `waitlist:{*}:events` matches hash-tagged channels and nothing else). Gateway code passes a `javac` syntax check. The frontend runs against a mock STOMP gateway speaking the same contract: **live mode made 0 REST snapshot calls in 3 s**; after killing the gateway, the badge switched to `polling · 1s` (3 calls in 3 s) and positions kept updating; after restarting it, the client reconnected, resubscribed (2 subscriptions restored), resynced, and polling stopped. **On your machine:** `FanoutFlowTest` runs the real gateway on a random port against real Redis and a fake Core (the JDK's own HTTP server), driven by a real STOMP client: initial state on subscribe, position update when someone ahead leaves (no event for the watcher itself), immediate RESERVED push, no resend of unchanged state, dashboard snapshot relay, and zero pushes for unwatched waitlists.


### Brick 6 — Groups (2026-09-24)

Kept deliberately simple.

- **STRICT:** one `waitlist_entry` for the whole group (owned by the creator, `group_id` set) → one queue member, one serving slot. Each member confirms via `POST /api/waitlists/{id}/groups/{groupId}/confirm`, and the entry becomes CONFIRMED when the last one does. The normal `/entries/{id}/confirm` is refused for a STRICT group entry (`GROUP_CONFIRM_REQUIRED`), so the creator can't confirm for everyone. Expiry and decline work as for any entry.
- **PARTIAL:** one entry per member, tagged with `group_id`. Members move and confirm independently; the group is just a label.
- **Create:** `POST /api/waitlists/{id}/groups` `{memberIds}` with the caller as creator. 2–8 people; rejected if anyone is already active on the waitlist.
- Snapshot rows carry `groupId` / `groupSize` (and `groupConfirmed` for reserved slots), so the dashboard shows "group of 3" and "1/3 confirmed".
- No schema change: V1 already had `waitlist_group` and `group_member`.

**Known limits (fine for now):** PARTIAL members join one after another, so another join can land between them. A STRICT member who isn't the creator could still join the same waitlist on their own afterwards (only checked at group creation). Referral credits aren't group-aware.

Tests: `GroupFlowTest` (STRICT = one entry + one slot, confirm needs everyone, PARTIAL = separate entries, already-queued member rejected).


### Brick 7 — Auth, tenant isolation, admission (2026-09-24)

Backend kept small; no Spring Security filter chain.

- **Auth:** `POST /api/auth/register | login | guest` → HS256 JWT (`jjwt`), subject = user id, 12 h TTL. Passwords use BCrypt (`spring-security-crypto` only). Guests get a real token but can't log in later. A `@CurrentUser UUID` argument resolver reads `Authorization: Bearer …` and returns 401 if it's missing, forged or expired. It replaces the `X-User-Id` header everywhere. Cancel now also checks ownership (404 if not yours).
- **Tenant isolation:** `PATCH /api/waitlists/{id}` needs `X-Api-Key`. Unknown key → 401; a key from a different tenant → **404** (not 403, so other tenants' waitlists can't be probed). End users are global accounts; tenants administer only their own waitlists.
- **Rate limit:** an interceptor on write requests under `/api/waitlists/**`, 20 per user (or per IP when anonymous) per 10 s, as a Redis fixed window (`SET NX EX`, then `INCR`). Over the limit → 429 + `Retry-After`.
- **Idempotency keys:** a filter on POSTs carrying `Idempotency-Key`. `SET NX` marks the key PENDING; the finished response (status + body) is stored for 24 h and replayed with `Idempotent-Replay: true`. A duplicate arriving while the first is still running → 409. 5xx responses aren't stored, so real retries can go through. The frontend generates one key per action and retries once with the same key on a network failure.
- **Not done (known):** the WebSocket gateway doesn't check tokens yet (it only serves position data). Token refresh/revocation isn't implemented; tokens simply expire.

Tests: `AuthAndAdmissionTest` (MockMvc: 401 without/forged token, register + login + wrong password, tenant key 401/404/200, 20 OK then 429 with Retry-After, idempotent replay of group creation).

**Frontend** (deliberately rich): `motion` (Framer Motion) replaces the hand-written FLIP. Rows slide in and out and glide to new positions, and a bumped row flashes with a floating "↑N". A sliding tab highlight, spring-animated counters, countdown rings on serving slots (amber and pulsing near the end), confetti on confirm, and a toast stack (your turn / rate-limited / errors). An animated sign-in modal (guest / log in / register) and an identity chip. A "Spam 30 requests" button to show the rate limiter, and an API-key field on the config card to show tenant isolation. Actions only appear for people this browser holds a token for.


### Final step — Proofs & CI (2026-09-24)

- **Concurrency proof** (`ConcurrencyProofTest`): 100 people waiting, 3 slots, 200 threads released together by a latch. The naive version (ZCARD check, then ZPOPMIN + ZADD) is asserted to **over-book**; `promote.lua` is asserted to reserve **exactly 3**, and they're the first three in line. Both counts are printed in the test log (`NAIVE: n reservations for 3 slots` / `LUA: 3 …`). The naive version has a 2 ms pause between check and act, standing in for the normal gap between a read and a write in a real service. The same experiment with raw redis-cli gave 60 vs 3 (Brick 3).
- **Load test** (`load/join-burst.js`, k6): 500 guest accounts are created in `setup()`, then 500 virtual users each join the same waitlist at once and read their position. Thresholds: <1% failed requests, join p95 < 300 ms / p99 < 500 ms, position p95 < 100 ms. It runs in Docker on the compose network and exports `load/report.html` (k6 web dashboard with latency graphs). The script was validated against a mock (100% of checks passing, report generated); real numbers come from running it against the stack.
- **CI** (`.github/workflows/ci.yml`): on every push and PR, `mvn -B verify` for Core and the gateway (Testcontainers on GitHub's Ubuntu runners), `npm ci && lint && build` for the frontend, then `docker compose build` once all three pass. Test reports are uploaded as artifacts when a job fails. This is also the first place every test runs automatically.


### Performance pass (2026-09-24)

**First k6 run** (500 simultaneous joins, laptop + Docker Desktop): 0% errors, ~960 req/s, join p50 306 ms / p95 420 ms / p99 445 ms, position p95 108 ms. The time was **queueing for DB connections**: Hikari pool = 20, and each join made ~6–8 short Postgres calls. Position reads are mostly Redis, hence ~10× faster.

**Changes:**
- Hikari pool 20 → 50 (`DB_POOL_SIZE`), reverted after run 2, see below.
- Waitlist config cached in `WaitlistService` for 5 s, cleared on update. It's read on nearly every request and rarely changes. Trade-off: another Core instance may use an old config for up to 5 s.
- Join no longer runs a separate "does this user exist?" query. The `user_id` foreign key enforces it and is mapped to `USER_NOT_FOUND`.
- Result: a join makes ~3–4 Postgres calls instead of ~6–8.
- k6 script: the burst is followed by a 60 s steady phase (50 joins/s), so the HTML report has enough data. Per-phase thresholds set from the first run.

**Second run** (pool 50 + cache + one query less): 0 errors in 10,813 requests. **Steady 50 joins/s: p50 11 ms, p95 14 ms.** Burst: p50 511 ms, p99 764 ms, i.e. *slower* than run 1, while the 500 joins still finished in ~1 s both times.

**Lesson (pool sizing):** the burst is bound by CPU (a small Docker VM, ~500 joins/s), not by connections. A bigger pool doesn't add capacity; it moves the queue from the app (cheap waiting) into Postgres (50 active queries fighting over a few cores), so each request gets slower. The pool went back to **20** (rule of thumb ~ cores × 2); the cache and the dropped query stay. The burst threshold is now a capacity check (p99 < 1 s); the latency target is the steady phase (p95 < 150 ms, measured 14 ms).

**Next lever, if the burst mattered:** fewer DB round trips per join (batch the join insert + credit lookup), or put an admission queue in front (§12.1), rather than more connections.

---

*Last updated: 2026-09-24*
