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

> **Open dependency on this decision:** rebuilding requires PostgreSQL to store something equivalent to score order (e.g., `join_sequence` + cumulative bump amount), not just timestamps/state. This must be nailed down during schema design — see [§13](#13-open--not-yet-decided).

**Decision 2 — Bank referral credits within the same waitlist.** When A refers B and earns a bump, but A is already at position 1 or in soft reservation, the credit is banked in a `referral_credit` ledger per user per waitlist in PostgreSQL instead of being discarded. Credits only activate when the person is not already at the front. A state check runs before applying credits to prevent double-dipping.

**Decision 3 — Configurable group policy.** Each waitlist has a `groupPolicy` field: `STRICT` (whole group moves as a unit, nobody proceeds until all members reach the front together) or `PARTIAL` (members proceed individually as slots open). Group members track individual states even under `STRICT`, because you need to know who has confirmed vs. not once the front is reached.

---

## 6. Why Redis Sorted Set

A Sorted Set orders members by a floating-point score automatically. Lower score = higher position (position 1 = lowest score). A referral bump is one `ZINCRBY waitlist:{id}:queue -N {userId}` — O(log N), atomic, no locks. Storing queue order as PostgreSQL rows instead would require touching O(N) rows per bump — breaks at scale.

## 7. Why Redis + PostgreSQL Together (Not One or the Other)

Redis does sorted-set ops in O(log N) in memory, no disk — essential for join/bump/position-read in the hot path. PostgreSQL is the source of truth for durability, audit, cross-waitlist queries, and crash recovery. CQRS-adjacent split: Redis = write-optimized live state, PostgreSQL = durable queryable record.

## 8. Why Pub/Sub for Real-Time (Not Polling)

On any position change, the queue service publishes to `waitlist:{id}:events`. The broadcast service subscribes and fans out to WebSocket clients immediately. Decouples the write path from the notification path. Polling Redis from every client every second would mean thousands of requests/sec with mostly no new data.

> **Known gap at scale:** Redis Pub/Sub is fire-and-forget — a disconnected/slow subscriber just misses messages, no replay. See [§12.2](#122-real-time-delivery).

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

**Deployment grouping:**

- **Core Queue Service (one modular monolith)** — Queue + Reservation + Referral/Credit + Group (+ Tenant/Admin) as hard-interfaced internal modules, not separate deployables. Reasoning: these four share the same transaction boundary and the same hot-path data (the ZSET + the entry row) — every join/bump/reservation touches the same key and row in the same logical operation, so they scale 1:1 with each other. Splitting them today buys nothing but network hops and distributed-transaction pain (e.g. Referral calling back into Queue to apply a bump would cross a network boundary for no scaling benefit).
- **Four standalone services from day one** — Admission/Gateway, Broadcast, WebSocket Gateway, Fraud/Abuse. Each scales on a genuinely different axis: Gateway on request rate at the edge, WebSocket Gateway on *concurrent connections* (not request rate — a quiet waitlist with 500K idle watchers costs as much as a busy one), Broadcast on event volume, Fraud on its own evolution velocity (rules/scoring change independently of queue logic). Note this lines up exactly with the three areas already tagged 🔵 deep-design in [§12](#12-production-scale-system-design-concept-catalog) (Admission, Real-time delivery, Security/fraud) — the places that earned full design depth are the same places that earn an actual network boundary.

Guiding principle to defend in an interview: **monolith-first, extract services along proven/divergent scaling boundaries** — not microservices-by-default.

---

## 14. Open / Not Yet Decided

- PostgreSQL schema design (entity definitions, columns, foreign keys, indexes) — **blocked on** deciding the Decision-1 rebuild-from-Postgres column (score-equivalent: `join_sequence` + cumulative bump amount)
- How a `STRICT` group occupies the Redis Sorted Set (single `group:{groupId}` member with individual member states tracked separately in PostgreSQL — needs to be finalized before schema)
- Spring Boot project skeleton
- Any code whatsoever

**Next discussion step:** request-level data flow — trace one concrete operation (a join into a product-drop waitlist) end-to-end through every service locked in [§13](#13-service-boundaries--deployment-units-locked-2026-06-26).

---

## 15. Teaching Style Preference

Explain every architectural decision with: the reason for the choice, the tradeoffs, what would break at scale, and what alternatives exist. Never just give code — make it understandable enough to defend in a Flipkart interview. Discuss and lock architecture before writing any code.

---

*Last updated: 2026-06-26*
