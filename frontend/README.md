# WaaS dashboard

React + Vite + TypeScript. Visualises the live queue and system health.

```bash
npm install
npm run dev        # http://localhost:5173 — proxies /api, /ws and health to localhost:8080/8081
```

In `docker compose` it is built and served by nginx with the same routes (`nginx/default.conf`).
