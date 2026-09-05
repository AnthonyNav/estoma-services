#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const collectionPath = process.argv[2] ?? resolve(
  root, 'e2e/postman/workflows/wash-student-front-v1.postman_collection.json');
const environmentPath = process.argv[3] ?? resolve(
  root, 'e2e/postman/workflows/wash-student-front-v1.environment.example.json');

const collection = JSON.parse(readFileSync(collectionPath, 'utf8'));
const environment = JSON.parse(readFileSync(environmentPath, 'utf8'));
const requests = (collection.item ?? []).map((item) => ({ name: item.name, request: item.request }));
const errors = [];

const expected = [
  ['01 Login synthetic student', 'POST', '/api/v1/auth/login', false, false],
  ['02 WASH-STU-01 home without appointment', 'GET', '/api/v1/wash/student/home', true, false],
  ['03 WASH-STU-02 form context', 'GET', '/api/v1/wash/appointments/form-context', true, false],
  ['04 WASH-STU-02 availability cache-aside', 'GET', '/api/v1/wash/appointments/availability', true, false],
  ['05 WASH-STU-02 schedule appointment', 'POST', '/api/v1/wash/appointments', true, true],
  ['06 Poll schedule operation', 'GET', '/api/v1/operations/{scheduleOperationId}', true, false],
  ['07 WASH-STU-01 home converges to scheduled', 'GET', '/api/v1/wash/student/home', true, false],
  ['08 WASH-STU-05 cancel appointment', 'POST', '/api/v1/wash/appointments/{appointmentId}/cancel', true, true],
  ['09 Poll cancel operation', 'GET', '/api/v1/operations/{cancelOperationId}', true, false],
  ['10 WASH-STU-01 home converges to cancelled', 'GET', '/api/v1/wash/student/home', true, false],
  ['11 Logout synthetic student', 'POST', '/api/v1/auth/logout', true, false],
];

function rawUrl(request) {
  return typeof request.url === 'string' ? request.url : request.url?.raw;
}

function normalizedPath(request) {
  const raw = rawUrl(request)?.replace('{{baseUrl}}', 'http://workflow.invalid') ?? '';
  return decodeURIComponent(new URL(raw).pathname).replace(/{{([^}]+)}}/g, '{$1}');
}

if (requests.length !== expected.length) {
  errors.push(`expected ${expected.length} ordered requests, found ${requests.length}`);
}
expected.forEach(([name, method, path, bearer, idempotency], index) => {
  const actual = requests[index];
  if (!actual) return;
  if (actual.name !== name) errors.push(`request ${index + 1} name mismatch`);
  if (actual.request?.method !== method) errors.push(`${name}: expected ${method}`);
  if (normalizedPath(actual.request) !== path) errors.push(`${name}: path mismatch`);
  if ((actual.request?.auth?.type === 'bearer') !== bearer) errors.push(`${name}: bearer mismatch`);
  const hasIdempotency = (actual.request?.header ?? []).some((header) =>
    !header.disabled && header.key?.toLowerCase() === 'idempotency-key');
  if (hasIdempotency !== idempotency) errors.push(`${name}: Idempotency-Key mismatch`);
});

const scheduleBody = requests.find(({ name }) => name.startsWith('05 '))?.request?.body?.raw ?? '';
for (const required of ['appointmentTimeSlotId', 'courseSectionId', 'appointmentType',
  'instrumentCount', 'pieceType', 'exceptionalAuthorizationId', 'regulationAccepted']) {
  if (!scheduleBody.includes(`\"${required}\"`)) errors.push(`schedule body misses ${required}`);
}
for (const forbidden of ['studentAccountId', 'serviceDate', 'cabinId', 'tankId']) {
  if (scheduleBody.includes(`\"${forbidden}\"`)) errors.push(`schedule body trusts ${forbidden}`);
}

const cancelBody = requests.find(({ name }) => name.startsWith('08 '))?.request?.body?.raw ?? '';
if (!cancelBody.includes('"expectedVersion"')) errors.push('cancel body misses expectedVersion');
if (cancelBody.includes('"reason"')) errors.push('WASH-STU-05 must not invent a cancellation reason');

function eventSource(item, listen) {
  return (item?.event ?? [])
    .filter((event) => event.listen === listen)
    .flatMap((event) => event.script?.exec ?? [])
    .join('\n');
}

const loginTests = eventSource(collection.item?.[0], 'test');
const logoutTests = eventSource(collection.item?.[10], 'test');
if (!loginTests.includes("pm.environment.set('runtimeAccessToken'")) {
  errors.push('login must persist the runtime token for failure-safe logout');
}
if (!logoutTests.includes("pm.environment.unset('runtimeAccessToken'")) {
  errors.push('successful logout must clear the failure-safe runtime token');
}

const environmentValues = new Map((environment.values ?? []).map((entry) => [entry.key, entry.value]));
for (const secret of ['baseUrl', 'loginIdentifier', 'password', 'runtimeAccessToken', 'runKey',
  'fixtureCourseSectionId', 'fixtureAppointmentTimeSlotId']) {
  if (environmentValues.get(secret) !== '') errors.push(`${secret} must be blank in the example environment`);
}

const requestUrls = requests.map(({ request }) => rawUrl(request)).join('\n');
if (/https?:\/\/(?!{{baseUrl}})/.test(requestUrls)) errors.push('request URLs must not contain a concrete host');
if (/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.test(
  JSON.stringify(collection))) errors.push('collection must not contain a concrete UUID');

if (errors.length) {
  console.error(`Wash-student workflow gate failed (${errors.length}):`);
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}
console.log(`Wash-student workflow gate passed: ${requests.length} ordered requests; runtime credentials and fixture identifiers remain blank.`);
