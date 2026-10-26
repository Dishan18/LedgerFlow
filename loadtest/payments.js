// k6 load test: create + capture payments at a constant arrival rate.
//
// Locally (docker compose stack running):
//   docker run --rm --network ledgerflow_default -v "$PWD/loadtest:/scripts" grafana/k6:2.3.0 \
//     run -e BASE=http://ledgerflow-api:8080 -e RATE=200 /scripts/payments.js
// Against AWS: -e BASE=http://<alb dns> -e TOKEN=$(python tools/mint_token.py --secret ... --merchant ...)
//
// RATE is iterations per second; each iteration is two requests (create, then capture).

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '2m';
const MERCHANT = '11111111-1111-1111-1111-111111111111';

export const options = {
  scenarios: {
    payments: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create}': ['p(99)<1500'],
    'http_req_duration{name:capture}': ['p(99)<500'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (__ENV.TOKEN) {
    return { token: __ENV.TOKEN };
  }
  const res = http.post(`${BASE}/dev/token?merchantId=${MERCHANT}`);
  return { token: res.json('access_token') };
}

export default function (data) {
  const auth = { Authorization: `Bearer ${data.token}` };
  // Spread load over the 1,000 seeded customers so we measure the system, not one hot row.
  const customer = `cust_${String(1 + Math.floor(Math.random() * 1000)).padStart(4, '0')}`;

  const created = http.post(`${BASE}/v1/payments`, JSON.stringify({ customerId: customer, amount: 10 }), {
    headers: Object.assign({ 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, auth),
    tags: { name: 'create' },
  });
  check(created, { 'created 201': (r) => r.status === 201 });
  if (created.status !== 201 || created.json('status') !== 'AUTHORIZED') {
    return; // declined or pending at the bank: nothing to capture
  }

  const captured = http.post(`${BASE}/v1/payments/${created.json('id')}/capture`, null, {
    headers: Object.assign({ 'Idempotency-Key': uuid() }, auth),
    tags: { name: 'capture' },
  });
  check(captured, { 'captured 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const reqs = data.metrics.http_reqs.values;
  const trend = (name) => {
    const m = data.metrics[`http_req_duration{name:${name}}`];
    return m ? m.values : {};
  };
  const create = trend('create');
  const capture = trend('capture');
  const line = (label, v) =>
    `${label.padEnd(8)} p50=${(v['p(50)'] || 0).toFixed(1)}ms  p95=${(v['p(95)'] || 0).toFixed(1)}ms  p99=${(v['p(99)'] || 0).toFixed(1)}ms`;
  const text = [
    `target rate: ${RATE} payments/s for ${DURATION}`,
    `throughput: ${reqs.rate.toFixed(1)} requests/s (${reqs.count} requests)`,
    `failed requests: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`,
    line('create', create),
    line('capture', capture),
    `dropped iterations: ${data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0}`,
  ].join('\n');
  return {
    stdout: text + '\n',
    [`/scripts/results/summary-${RATE}.json`]: JSON.stringify(data, null, 2),
  };
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
