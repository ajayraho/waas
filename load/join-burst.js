// k6 load test: a burst of users joining at once, then a steady stream.
//
// Run against the docker compose stack (from the repo root, PowerShell):
//   docker run --rm -i --network waas_default -e K6_NO_USAGE_REPORT=true -v "${PWD}/load:/scripts" `
//     -e BASE_URL=http://core-queue-service:8080 `
//     -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT=/scripts/report.html `
//     grafana/k6 run /scripts/join-burst.js
//
// Then open load/report.html for the latency graphs.
// Knobs: -e BURST=500  -e RATE=50 (joins/s in the steady phase)  -e DURATION=60  -e WAITLIST_ID=<uuid>

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WAITLIST = __ENV.WAITLIST_ID || '10000000-0000-0000-0000-000000000002'; // seeded "Beta Access"
const BURST = Number(__ENV.BURST || 500);
const RATE = Number(__ENV.RATE || 50);
const DURATION = Number(__ENV.DURATION || 60);
const STEADY = RATE * DURATION;

export const options = {
  setupTimeout: '300s',
  scenarios: {
    // 1) everyone at once: the flash-sale moment
    burst: { executor: 'shared-iterations', vus: BURST, iterations: BURST, maxDuration: '60s' },
    // 2) then a steady stream of new joins, starting after the burst has settled
    steady: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION}s`,
      preAllocatedVUs: 50, maxVUs: 200, startTime: '10s',
    },
  },
  // Limits set from the first real run (laptop, Docker Desktop). A burst queues for DB
  // connections, so its limits are looser than the steady phase's.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:burst,name:join}': ['p(99)<600'],
    'http_req_duration{scenario:steady,name:join}': ['p(95)<150'],
    'http_req_duration{name:position}': ['p(95)<150'],
  },
};

function signUp(count, prefix) {
  const tokens = [];
  for (let i = 0; i < count; i += 50) {
    const batch = [];
    for (let j = i; j < Math.min(i + 50, count); j++) {
      batch.push(['POST', `${BASE}/api/auth/guest`, JSON.stringify({ name: `${prefix}-${j}` }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup' } }]);
    }
    for (const r of http.batch(batch)) {
      if (r.status !== 201) throw new Error(`guest signup failed: HTTP ${r.status}`);
      tokens.push(r.json('token'));
    }
  }
  return tokens;
}

// One guest account (and JWT) per join, created before the timed part starts.
export function setup() {
  // constant-arrival-rate can start a few more iterations than rate x duration, so keep spares
  return { burst: signUp(BURST, 'burst'), steady: signUp(Math.ceil(STEADY * 1.1) + 10, 'steady') };
}

export default function (data) {
  const tokens = exec.scenario.name === 'burst' ? data.burst : data.steady;
  const token = tokens[exec.scenario.iterationInTest % tokens.length];
  const auth = { headers: { Authorization: `Bearer ${token}` } };

  const join = http.post(`${BASE}/api/waitlists/${WAITLIST}/entries`, null, { ...auth, tags: { name: 'join' } });
  check(join, { 'joined (201)': (r) => r.status === 201 });

  // what the client does next: ask where it stands (only if the join worked;
  // a failed request is already counted in http_req_failed)
  const entryId = join.status >= 200 && join.status < 300 ? join.json('entryId') : null;
  if (entryId) {
    const pos = http.get(`${BASE}/api/waitlists/${WAITLIST}/entries/${entryId}`, { tags: { name: 'position' } });
    check(pos, { 'position (200)': (r) => r.status === 200 });
  }
}
