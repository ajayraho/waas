import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev proxy mirrors nginx/default.conf exactly, so the app only ever uses
// relative URLs and behaves the same under `npm run dev` and docker compose.
const CORE = process.env.CORE_URL ?? 'http://localhost:8080'
const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': CORE,
      '/core/actuator': { target: CORE, rewrite: (p) => p.replace(/^\/core/, '') },
      '/gateway': { target: GATEWAY, rewrite: (p) => p.replace(/^\/gateway/, '') },
      '/ws': { target: GATEWAY, ws: true },
    },
  },
})
