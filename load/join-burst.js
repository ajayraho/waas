// k6 load test: a burst of users joining one waitlist at the same moment.
//
// Run against the docker compose stack (from the repo root, PowerShell):
//   docker run --rm -i --network waas_default -e K6_NO_USAGE_REPORT=true -v "${PWD}/load:/scripts" `
//     -e BASE_URL=http://core-queue-service:8080 `
//     -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT=/scripts/report.html `
//     grafana/k6 run /scripts/join-burst.js
//
// Then open load/report.html for the latency graphs.
// Knobs: -e USERS=1000  -e WAITLIST_ID=<uuid>

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WAITLIST = __ENV.WAITLIST_ID || '10000000-0000-0000-0000-000000000002'; // seeded "Beta Access"
const USERS = Number(__ENV.USERS || 500);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    // every virtual user joins exactly once, all at the same time
    burst: { executor: 'shared-iterations', vus: USERS, iterations: USERS, maxDuration: '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:join}': ['p(95)<300', 'p(99)<500'],
    'http_req_duration{name:position}': ['p(95)<100'],
  },
};

// One guest account (and JWT) per virtual user, created before the timed part starts.
export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i += 50) {
    const batch = [];
    for (let j = i; j < Math.min(i + 50, USERS); j++) {
      batch.push(['POST', `${BASE}/api/auth/guest`, JSON.stringify({ name: `load-${j}` }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup' } }]);
    }
    for (const r of http.batch(batch)) {
      if (r.status !== 201) throw new Error(`guest signup failed: HTTP ${r.status}`);
      tokens.push(r.json('token'));
    }
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[exec.scenario.iterationInTest];
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
