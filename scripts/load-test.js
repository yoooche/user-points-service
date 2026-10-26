import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const profiles = {
  smoke: { write: 5, total: 20, leaderboard: 5, vus: 10, max: 100 },
  // 70M web visits/month * (1 web + 3 app) / 30 days * 10 peak factor.
  // Assumes one points write and four reads per session, with 80/20 read split.
  modeled: { write: 1080, total: 3456, leaderboard: 864, vus: 100, max: 500 },
  // The original DESIGN.md estimate, retained as an exploratory stress target.
  stress: { write: 6000, total: 20000, leaderboard: 5000, vus: 100, max: 500 },
};
const profileName = __ENV.PROFILE || 'smoke';
const profile = profiles[profileName];
if (!profile) throw new Error('PROFILE must be smoke, modeled or stress');
const successfulAdds = new Counter('successful_adds');
const successfulTotals = new Counter('successful_total_reads');
const successfulLeaderboards = new Counter('successful_leaderboard_reads');

// Each scenario iteration makes one request. Rates are requests per second.
const base = (__ENV.BASE_URL || 'http://localhost:8080/user-points-service').replace(/\/$/, '');
function positiveInt(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

const users = positiveInt('USERS', 20);
const preAllocatedVUs = positiveInt('PRE_ALLOCATED_VUS', profile.vus);
const maxVUs = positiveInt('MAX_VUS', profile.max);
if (maxVUs < preAllocatedVUs) throw new Error('MAX_VUS must be >= PRE_ALLOCATED_VUS');

function scenario(fn, rate) {
  return {
    executor: 'constant-arrival-rate',
    exec: fn,
    rate,
    timeUnit: '1s',
    duration: __ENV.DURATION || '30s',
    preAllocatedVUs,
    maxVUs,
    gracefulStop: '15s',
  };
}

export const options = {
  setupTimeout: '2m',
  teardownTimeout: '2m',
  scenarios: {
    add: scenario('addPoints', positiveInt('WRITE_RPS', profile.write)),
    total: scenario('getTotal', positiveInt('TOTAL_RPS', profile.total)),
    leaderboard: scenario('getLeaderboard', positiveInt('LEADERBOARD_RPS', profile.leaderboard)),
  },
  thresholds: {
    'http_req_failed{phase:load}': ['rate<0.01'],
    'checks{phase:load}': ['rate==1'],
    'http_req_duration{scenario:add}': [`p(95)<${positiveInt('WRITE_P95_MS', 1000)}`],
    'http_req_duration{scenario:total}': [`p(95)<${positiveInt('READ_P95_MS', 500)}`],
    'http_req_duration{scenario:leaderboard}': [`p(95)<${positiveInt('READ_P95_MS', 500)}`],
    dropped_iterations: ['count==0'],
    successful_adds: ['count>0'],
    successful_total_reads: ['count>0'],
    successful_leaderboard_reads: ['count>0'],
  },
};

function params(endpoint, phase = 'load') {
  return {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: endpoint, phase },
    timeout: '10s',
  };
}

function body(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function cleanup(ids) {
  for (const id of ids) {
    const response = http.del(`${base}/points/${id}`, null, params('DELETE /points/:userId', 'cleanup'));
    if (response.status !== 204 && response.status !== 404) {
      console.warn(`Cleanup failed for ${id}: HTTP ${response.status}`);
    }
  }
}

export function setup() {
  // Returned setup data is shared by all scenarios; never delete pre-existing users.
  const prefix = `k6_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  const ids = [];
  try {
    for (let i = 0; i < users; i++) {
      const userId = `${prefix}_${i}`;
      ids.push(userId);
      const response = http.post(`${base}/points`, JSON.stringify({
        userId, amount: 100, reason: 'k6_seed',
      }), params('POST /points', 'setup'));
      if (response.status !== 201) {
        throw new Error(`Seed failed: HTTP ${response.status}; check BASE_URL and service dependencies`);
      }
      const warm = http.get(`${base}/points/${userId}`, params('GET /points/:userId', 'setup'));
      if (warm.status !== 200 || body(warm)?.total !== 100) {
        throw new Error(`Could not warm total cache for ${userId}`);
      }
    }
    console.log(`Testing ${base}; profile=${profileName}; users=${users}; prefix=${prefix}`);
    return { ids };
  } catch (error) {
    cleanup(ids);
    throw error;
  }
}

function user(data) {
  return data.ids[exec.scenario.iterationInTest % data.ids.length];
}

export function addPoints(data) {
  const userId = user(data);
  const response = http.post(`${base}/points`, JSON.stringify({
    userId, amount: 1, reason: 'k6_load',
  }), params('POST /points'));
  const json = body(response);
  const valid = check(response, {
    'add returns 201': (r) => r.status === 201,
    'add returns the created record': () => json?.userId === userId
      && json.amount === 1 && Number.isSafeInteger(json.id) && json.id > 0,
  }, { phase: 'load' });
  successfulAdds.add(valid ? 1 : 0);
}

export function getTotal(data) {
  const userId = user(data);
  const response = http.get(`${base}/points/${userId}`, params('GET /points/:userId'));
  const json = body(response);
  const valid = check(response, {
    'total returns 200': (r) => r.status === 200,
    'total returns a valid user total': () => json?.userId === userId
      && Number.isSafeInteger(json.total) && json.total >= 100,
  }, { phase: 'load' });
  successfulTotals.add(valid ? 1 : 0);
}

export function getLeaderboard() {
  const response = http.get(`${base}/points/leaderboard`, params('GET /points/leaderboard'));
  const json = body(response);
  const valid = check(response, {
    'leaderboard returns 200': (r) => r.status === 200,
    'leaderboard has valid, unique, sorted entries': () => Array.isArray(json)
      && json.length > 0 && json.length <= 10
      && json.every((entry) => entry !== null && typeof entry === 'object'
        && typeof entry.userId === 'string' && Number.isSafeInteger(entry.total) && entry.total > 0)
      && new Set(json.map((entry) => entry.userId)).size === json.length
      && json.every((entry, index) => (index === 0 || json[index - 1].total > entry.total
          || (json[index - 1].total === entry.total && json[index - 1].userId < entry.userId))),
  }, { phase: 'load' });
  successfulLeaderboards.add(valid ? 1 : 0);
}

export function teardown(data) {
  cleanup(data.ids);
}
