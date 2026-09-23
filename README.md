# Waitlist-as-a-Service

A multi-tenant waitlist backend: live positions over WebSocket, rank-based referral bumps,
capacity-based soft reservations with auto-expiry, and group joining.
Design rationale lives in [ARCHITECTURE.md](ARCHITECTURE.md).

## Run it

Needs only Docker (images build inside containers, so no local Java/Maven/Node is required):

```bash
docker compose up --build
```

| What | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| Core health | http://localhost:8080/actuator/health |
| Gateway health / fan-out stats | http://localhost:8081/actuator/health · http://localhost:8081/stats |

Reset all data: `docker compose down -v`.

## Layout

| Path | What |
|---|---|
| `core-queue-service/` | Spring Boot modular monolith: queue, reservation, referral, group, tenant, admission |
| `websocket-gateway/` | Spring Boot STOMP gateway: holds sockets, fans out Redis events |
| `frontend/` | React + Vite + TypeScript dashboard (nginx in compose) |
| `core-queue-service/src/main/resources/db/` | Flyway migrations (`migration/`) and demo seed (`seed/`, `demo` profile only) |

## Local development

```bash
docker compose up -d postgres redis
cd core-queue-service && mvn spring-boot:run -Dspring-boot.run.profiles=demo
cd websocket-gateway  && mvn spring-boot:run
cd frontend           && npm install && npm run dev      # http://localhost:5173
```

Tests use Testcontainers (Docker must be running): `mvn test` in either service.
Without a local JDK, run them in a container (Docker Desktop):

```powershell
docker run --rm -v "${PWD}/core-queue-service:/src" -w /src -v //var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B test
```

## Live updates (STOMP over WebSocket at `/ws`)

| Subscribe to | You get |
|---|---|
| `/topic/waitlists/{id}/queue` | the dashboard snapshot, pushed when the queue changes |
| `/topic/waitlists/{id}/entries/{entryId}` | one entry's state / position / countdown |
| `/app/…` (same paths) | the current state, once, on subscribe |

## Auth

`POST /api/auth/guest {name}`, `/register {name,email,password}` or `/login {email,password}` → `{ token, user }`.
Send `Authorization: Bearer <token>` on user actions. Tenant admin calls send `X-Api-Key` (demo key: `demo-api-key-001`).
Write requests are rate-limited (20 per 10 s per user → 429). POSTs accept an `Idempotency-Key` header.

## API

| Method | Path | |
|---|---|---|
| `GET` | `/api/waitlists` | active waitlists |
| `POST` | `/api/users` | `{ "name": "Anshu" }` |
| `POST` | `/api/waitlists/{id}/entries?ref={userId}` | join (optionally through a referral link) |
| `GET` | `/api/waitlists/{id}/entries/{entryId}` | position / state |
| `DELETE` | `/api/waitlists/{id}/entries/{entryId}` | cancel |
| `GET` | `/api/waitlists/{id}/queue` | dashboard snapshot |
| `POST` | `/api/waitlists/{id}/entries/{entryId}/confirm` | holder confirms |
| `POST` | `/api/waitlists/{id}/entries/{entryId}/decline` | holder releases the slot |
| `PATCH` | `/api/waitlists/{id}` | admin (`X-Api-Key`): `{ "servingCapacity": 3, "reservationWindowSeconds": 30 }` |
| `GET` | `/api/waitlists/{id}/users/{userId}/credits` | referral credit ledger |
| `GET` | `/api/waitlists/{id}/referrals` | recent referrals (credited + rejected) |
| `POST` | `/api/waitlists/{id}/groups` | `{ "memberIds": [...] }`; caller = creator |
| `POST` | `/api/waitlists/{id}/groups/{groupId}/confirm` | a STRICT group member confirms |
