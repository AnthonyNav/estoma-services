#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const collectionPath = resolve(root, 'e2e/postman/workflows/wash-supervisor-front-v1.postman_collection.json');
const environmentPath = resolve(root, 'e2e/postman/workflows/wash-supervisor-front-v1.environment.example.json');
const collection = JSON.parse(readFileSync(collectionPath, 'utf8'));
const environment = JSON.parse(readFileSync(environmentPath, 'utf8'));
const requests = (collection.item ?? []).map((item) => ({ name: item.name, request: item.request, event: item.event }));
const errors = [];

const expected = [
  ['01 Login synthetic supervisor', 'POST', '/api/v1/auth/login', false, false],
  ['02 WASH-SUP-06 baseline home', 'GET', '/api/v1/wash/supervision/home', true, false],
  ['03 Login synthetic student', 'POST', '/api/v1/auth/login', false, false],
  ['04 WASH-STU-01 home without appointment', 'GET', '/api/v1/wash/student/home', true, false],
  ['05 WASH-STU-02 form context', 'GET', '/api/v1/wash/appointments/form-context', true, false],
  ['06 WASH-STU-02 availability cache-aside', 'GET', '/api/v1/wash/appointments/availability', true, false],
  ['07 WASH-STU-02 schedule appointment', 'POST', '/api/v1/wash/appointments', true, true],
  ['08 Poll schedule operation', 'GET', '/api/v1/operations/{scheduleOperationId}', true, false],
  ['09 WASH-STU-01 home converges to scheduled', 'GET', '/api/v1/wash/student/home', true, false],
  ['10 WASH-SUP-06 home observes scheduled appointment', 'GET', '/api/v1/wash/supervision/home', true, false],
  ['11 WASH-SUP-01 lookup scheduled appointment', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['12 WASH-SUP-01 QR remains fail-closed', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['13 Repeat WASH-SUP-01 enrollment lookup', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['14 WASH-STU-05 cancel appointment', 'POST', '/api/v1/wash/appointments/{appointmentId}/cancel', true, true],
  ['15 Poll cancel operation', 'GET', '/api/v1/operations/{cancelOperationId}', true, false],
  ['16 WASH-STU-01 home converges to cancelled', 'GET', '/api/v1/wash/student/home', true, false],
  ['17 WASH-SUP-01 lookup converges to terminal appointment', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['18 WASH-SUP-06 home observes cancellation', 'GET', '/api/v1/wash/supervision/home', true, false],
  ['19 Logout synthetic student', 'POST', '/api/v1/auth/logout', true, false],
  ['20 Logout synthetic supervisor', 'POST', '/api/v1/auth/logout', true, false],
];

function rawUrl(request) {
  return typeof request.url === 'string' ? request.url : request.url?.raw;
}
function normalizedPath(request) {
  const raw = rawUrl(request)?.replace('{{baseUrl}}', 'http://workflow.invalid') ?? '';
  return decodeURIComponent(new URL(raw).pathname).replace(/{{([^}]+)}}/g, '{$1}');
}
function eventSource(item) {
  return (item.event ?? []).flatMap((event) => event.script?.exec ?? []).join('\n');
}

if (requests.length !== expected.length) errors.push(`expected ${expected.length} ordered requests, found ${requests.length}`);
expected.forEach(([name, method, path, needsBearer, idempotency], index) => {
  const actual = requests[index];
  if (!actual) return;
  if (actual.name !== name) errors.push(`request ${index + 1} name mismatch`);
  if (actual.request?.method !== method) errors.push(`${name}: expected ${method}`);
  if (normalizedPath(actual.request) !== path) errors.push(`${name}: path mismatch`);
  if ((actual.request?.auth?.type === 'bearer') !== needsBearer) errors.push(`${name}: bearer mismatch`);
  const hasIdempotency = (actual.request?.header ?? []).some((header) =>
    !header.disabled && header.key?.toLowerCase() === 'idempotency-key');
  if (hasIdempotency !== idempotency) errors.push(`${name}: Idempotency-Key mismatch`);
});

const supervisorRequests = requests.filter(({ name }) => name.includes('WASH-SUP') || name.includes('supervisor'));
for (const item of supervisorRequests.filter(({ name }) => !name.startsWith('01 '))) {
  const token = item.request?.auth?.bearer?.find((entry) => entry.key === 'token')?.value;
  if (token !== '{{supervisorAccessToken}}') errors.push(`${item.name}: must use the separate supervisor token`);
}
const lookupBodies = requests
  .filter(({ request }) => normalizedPath(request) === '/api/v1/wash/supervision/lookup')
  .map(({ request }) => request.body?.raw ?? '');
if (lookupBodies.filter((body) => body.includes('STUDENT_ENROLLMENT') && body.includes('{{fixtureStudentEnrollment}}')).length !== 3) {
  errors.push('three enrollment lookups must use only the runtime fixture enrollment');
}
if (!lookupBodies.some((body) => body.includes('"lookupType": "QR"') && body.includes('e2e-opaque-unprojected'))) {
  errors.push('QR gap request is missing');
}
const source = requests.map(eventSource).join('\n');
for (const required of [
  "pm.environment.set('runtimeAccessToken'",
  "pm.environment.set('runtimeSupervisorAccessToken'",
  "pm.environment.unset('runtimeAccessToken'",
  "pm.environment.unset('runtimeSupervisorAccessToken'",
  "body.nextAction).to.eql('ENTRY'",
  "body.nextAction).to.eql('NONE'",
  "body.code).to.eql('BFF.PROJECTION_UNAVAILABLE'",
  "body.washExecution).to.eql(null)",
]) if (!source.includes(required)) errors.push(`workflow assertions miss ${required}`);

const values = new Map((environment.values ?? []).map((entry) => [entry.key, entry.value]));
for (const blank of ['baseUrl', 'loginIdentifier', 'password', 'runtimeAccessToken',
  'supervisorLoginIdentifier', 'supervisorPassword', 'runtimeSupervisorAccessToken', 'runKey',
  'fixtureCourseSectionId', 'fixtureAppointmentTimeSlotId', 'fixtureStudentEnrollment']) {
  if (values.get(blank) !== '') errors.push(`${blank} must be blank in the example environment`);
}
if (/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.test(JSON.stringify(collection))) {
  errors.push('collection must not contain a concrete UUID');
}
if (/https?:\/\/(?!{{baseUrl}})/.test(requests.map(({ request }) => rawUrl(request)).join('\n'))) {
  errors.push('request URLs must not contain a concrete host');
}

if (errors.length) {
  console.error(`Wash-supervisor workflow gate failed (${errors.length}):`);
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}
console.log(`Wash-supervisor workflow gate passed: ${requests.length} ordered requests, two isolated sessions and no stored runtime values.`);
