// k6-planning.js - load test for the planning API (Phase 5 "performance at 10k products, 30 concurrent users").
//
// What it simulates: a month-end planning day. Virtual users sign in as the dev test users, then loop over the
// screens people actually use: cycle list, forecast grid (paged + search), allocation grid, permissions,
// notifications, and - for the Planner only - an allocation preview (the heaviest call: it re-runs the rules
// over every product without saving).
//
// Run (dev stack; tokens come from the DEV-ONLY "pifa-loadtest" password-grant client in realm-pifa-dev.json):
//   docker run --rm -i --network pifa-net -e BASE_URL=http://pifa-api:8080 \
//     -e KC_URL=http://keycloak:8081/realms/pifa-dev grafana/k6:0.57.0 run - < loadtest/k6-planning.js
// Options via env: VUS (default 30), DURATION (default 3m), CYCLE_ID (default: the newest cycle).
//
// Pass/fail: the thresholds below are the NFR targets (p95 < 1 s for reads, allocation preview p95 < 5 s,
// < 1% errors). k6 exits non-zero when a threshold fails, so this can gate a release pipeline.

import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KC = __ENV.KC_URL || 'http://localhost:8081/realms/pifa-dev';
const PASSWORD = __ENV.PASSWORD || 'dev-password'; // dev realm only

// Mix of users: mostly regional managers reading, a few forecasters, one planner running previews.
const USERS = ['anita.north', 'evan.east', 'wendy.west', 'sam.south', 'fiona.forecaster', 'pat.planner'];

export const options = {
  scenarios: {
    planning_day: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 30) },   // ramp up
        { duration: __ENV.DURATION || '3m', target: Number(__ENV.VUS || 30) }, // steady load
        { duration: '15s', target: 0 },                            // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<1000'],
    'http_req_duration{kind:preview}': ['p(95)<5000'],
  },
};

/**
 * Gets an access token for one user with the password grant.
 * @param {string} user username from the dev realm
 * @returns {string} the access token (valid 5 minutes; refreshed by tokenFor when old)
 */
function login(user) {
  const res = http.post(`${KC}/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'pifa-loadtest', username: user, password: PASSWORD,
  }, { tags: { kind: 'login' } });
  check(res, { 'login ok': (r) => r.status === 200 });
  return res.json('access_token');
}

// Per-VU token cache (each VU is its own JS runtime, so this is not shared between VUs).
let token = null;
let tokenAt = 0;

/**
 * Returns a valid token for this VU's user, logging in again after 4 minutes (tokens live 5).
 * @param {string} user username
 * @returns {string} bearer token
 */
function tokenFor(user) {
  if (!token || Date.now() - tokenAt > 240000) {
    token = login(user);
    tokenAt = Date.now();
  }
  return token;
}

/**
 * setup() runs once before the load: finds the cycle to test against (newest unless CYCLE_ID is given).
 * @returns {{cycleId: string}} data handed to every VU iteration
 */
export function setup() {
  if (__ENV.CYCLE_ID) return { cycleId: __ENV.CYCLE_ID };
  const t = login('pat.planner');
  const res = http.get(`${BASE}/api/v1/cycles`, { headers: { Authorization: `Bearer ${t}` } });
  const cycles = res.json();
  if (!cycles || cycles.length === 0) throw new Error('No planning cycle exists: generate synthetic data first');
  return { cycleId: cycles[0].id };
}

/**
 * One iteration = one user looking through the planning screens, with think time in between.
 * @param {{cycleId: string}} data from setup()
 */
export default function (data) {
  const user = USERS[(__VU - 1) % USERS.length];
  const params = { headers: { Authorization: `Bearer ${tokenFor(user)}` }, tags: { kind: 'read' } };
  const c = `${BASE}/api/v1/cycles/${data.cycleId}`;

  group('read screens', () => {
    const page = Math.floor(Math.random() * 20);
    const responses = http.batch([
      ['GET', `${BASE}/api/v1/cycles`, null, params],
      ['GET', `${c}/permissions`, null, params],
      ['GET', `${c}/forecast?page=${page}&size=100`, null, params],
      ['GET', `${c}/allocation?page=${page}&size=50`, null, params],
      ['GET', `${BASE}/api/v1/notifications`, null, params],
    ]);
    responses.forEach((r) => check(r, { 'read 200': (x) => x.status === 200 }));
  });
  sleep(1 + Math.random() * 2);

  group('search', () => {
    // Search by a two-digit fragment: matches some product names whatever the master data looks like.
    const r = http.get(`${c}/forecast?q=${Math.floor(Math.random() * 90) + 10}&size=100`, params);
    check(r, { 'search 200': (x) => x.status === 200 });
  });
  sleep(1 + Math.random() * 2);

  if (user === 'pat.planner') {
    group('allocation preview', () => {
      // responseCallback: count 409 as expected so it does not inflate http_req_failed.
      const r = http.post(`${c}/allocation/preview`, null,
        { headers: params.headers, tags: { kind: 'preview' }, responseCallback: http.expectedStatuses(200, 409) });
      // 409 is fine: the cycle may not be in the planning stage. Anything else must be 200.
      check(r, { 'preview 200/409': (x) => x.status === 200 || x.status === 409 });
    });
    sleep(5);
  }
}
