#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const collectionPath = resolve(root, 'e2e/postman/workflows/wash-supervisor-entry-front-v1.postman_collection.json');
const environmentPath = resolve(root, 'e2e/postman/workflows/wash-supervisor-entry-front-v1.environment.example.json');
const collection = JSON.parse(readFileSync(collectionPath, 'utf8'));
const environment = JSON.parse(readFileSync(environmentPath, 'utf8'));
const runner = readFileSync(resolve(root, 'e2e/runner/run-wash-supervisor-entry.sh'), 'utf8');
const studentFixture = readFileSync(resolve(root, 'e2e/fixtures/wash-student/fixture-db.sh'), 'utf8');
const folders = collection.item ?? [];
const requests = folders.flatMap((folder) => folder.item ?? []);
const errors = [];

const expected = [
  ['01 Login synthetic student', 'POST', '/api/v1/auth/login', false, false],
  ['02 WASH-STU-01 home without appointment', 'GET', '/api/v1/wash/student/home', true, false],
  ['03 WASH-STU-02 form context', 'GET', '/api/v1/wash/appointments/form-context', true, false],
  ['04 WASH-STU-02 availability cache-aside', 'GET', '/api/v1/wash/appointments/availability', true, false],
  ['05 WASH-STU-02 schedule appointment', 'POST', '/api/v1/wash/appointments', true, true],
  ['06 Poll schedule operation', 'GET', '/api/v1/operations/{scheduleOperationId}', true, false],
  ['07 WASH-STU-01 home converges to scheduled', 'GET', '/api/v1/wash/student/home', true, false],
  ['08 Logout synthetic student', 'POST', '/api/v1/auth/logout', true, false],
  ['09 Login synthetic supervisor', 'POST', '/api/v1/auth/login', false, false],
  ['10 WASH-SUP-01 lookup scheduled appointment', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['11 WASH-SUP-01 register arrival', 'POST', '/api/v1/wash/executions/arrivals', true, true],
  ['12 Poll arrival operation', 'GET', '/api/v1/operations/{arrivalOperationId}', true, false],
  ['13 WASH-SUP-01 lookup converges to pending entry', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['14 WASH-SUP-01 reject entry', 'POST', '/api/v1/wash/executions/{washExecutionId}/entry-decision', true, true],
  ['15 Poll rejected entry operation', 'GET', '/api/v1/operations/{entryDecisionOperationId}', true, false],
  ['16 WASH-SUP-01 lookup converges to rejected entry', 'POST', '/api/v1/wash/supervision/lookup', true, false],
  ['17 Logout synthetic supervisor', 'POST', '/api/v1/auth/logout', true, false],
];

function rawUrl(request) {
  return typeof request.url === 'string' ? request.url : request.url?.raw;
}
function normalizedPath(request) {
  const raw = rawUrl(request)?.replace('{{baseUrl}}', 'http://workflow.invalid') ?? '';
  return decodeURIComponent(new URL(raw).pathname).replace(/{{([^}]+)}}/g, '{$1}');
}
function source(item) {
  return (item.event ?? []).flatMap((event) => event.script?.exec ?? []).join('\n');
}

if (folders.length !== 2 || folders[0]?.name !== 'Prepare appointment'
    || folders[1]?.name !== 'Arrival and rejected entry') {
  errors.push('workflow must preserve the two explicit Newman phases');
}
if (requests.length !== expected.length) errors.push(`expected ${expected.length} ordered requests, found ${requests.length}`);
expected.forEach(([name, method, path, needsBearer, needsIdempotency], index) => {
  const item = requests[index];
  if (!item) return;
  if (item.name !== name) errors.push(`request ${index + 1} name mismatch`);
  if (item.request?.method !== method) errors.push(`${name}: method mismatch`);
  if (normalizedPath(item.request) !== path) errors.push(`${name}: path mismatch`);
  if ((item.request?.auth?.type === 'bearer') !== needsBearer) errors.push(`${name}: bearer mismatch`);
  const hasKey = (item.request?.header ?? []).some((header) =>
    !header.disabled && header.key?.toLowerCase() === 'idempotency-key');
  if (hasKey !== needsIdempotency) errors.push(`${name}: Idempotency-Key mismatch`);
});

for (const item of requests.slice(9)) {
  const token = item.request?.auth?.bearer?.find((entry) => entry.key === 'token')?.value;
  if (item.name !== '09 Login synthetic supervisor' && token !== '{{supervisorAccessToken}}') {
    errors.push(`${item.name}: must use only the supervisor token`);
  }
}

const arrival = requests.find((item) => item.name.includes('register arrival'));
if (arrival?.request?.body?.raw !== '{\n  "appointmentId": "{{appointmentId}}"\n}') {
  errors.push('arrival body is not the exact server-enriched contract');
}
const decision = requests.find((item) => item.name.includes('reject entry'));
const expectedDecision = '{\n  "identityConfirmed": true,\n  "requirementsSatisfied": false,\n  "decision": "REJECTED",\n  "rejectionReason": "E2E_REQUIREMENTS_NOT_SATISFIED",\n  "expectedVersion": {{washExecutionVersion}}\n}';
if (decision?.request?.body?.raw !== expectedDecision) errors.push('rejected-entry body does not match the closed V1 mapping');

const allSource = requests.map(source).join('\n');
for (const required of [
  "body.publicOperation).to.eql('wash-register-arrival')",
  "body.publicOperation).to.eql('wash-decide-entry')",
  '{"status":"PENDING_ENTRY"}',
  '{"status":"ENTRY_REJECTED"}',
  "body.nextAction).to.eql('ENTRY')",
  "body.nextAction).to.eql('ENTRY_DECISION')",
  "body.nextAction).to.eql('NONE')",
  "body.washExecution.executionVersion).to.eql(1)",
  "body.washExecution.executionVersion).to.eql(2)",
  "pm.environment.set('washExecutionId'",
  "pm.environment.unset('runtimeAccessToken'",
  "pm.environment.unset('runtimeSupervisorAccessToken'",
]) if (!allSource.includes(required)) errors.push(`workflow assertions miss ${required}`);
if (allSource.includes('pm.collectionVariables')) errors.push('two-phase workflow must persist state only in its ephemeral environment');
if (!runner.includes('E2E_WASH_SLOT_PROFILE=arrival')
    || !runner.includes('"$entry_fixture" bootstrap')
    || !runner.includes('run_phase prepare')
    || !runner.includes('run_phase decision')) {
  errors.push('runner must use the arrival window and owner barrier between its two phases');
}
if (!studentFixture.includes('arrival)') || !studentFixture.includes('arrival_tolerance_offset=20')) {
  errors.push('student fixture does not retain the opt-in arrival slot profile');
}

const values = new Map((environment.values ?? []).map((entry) => [entry.key, entry.value]));
for (const blank of ['baseUrl', 'loginIdentifier', 'password', 'runtimeAccessToken',
  'supervisorLoginIdentifier', 'supervisorPassword', 'runtimeSupervisorAccessToken',
  'fixtureStudentAccountId', 'fixtureSupervisorAccountId', 'runKey', 'fixtureCourseSectionId',
  'fixtureAppointmentTimeSlotId', 'fixtureStudentEnrollment']) {
  if (values.get(blank) !== '') errors.push(`${blank} must be blank in the example environment`);
}
const serialized = JSON.stringify(collection);
if (/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.test(serialized)) {
  errors.push('collection must not contain a concrete UUID');
}
if (/https?:\/\/(?!{{baseUrl}})/.test(requests.map(({ request }) => rawUrl(request)).join('\n'))) {
  errors.push('request URLs must not contain a concrete host');
}

if (errors.length) {
  console.error(`Wash-supervisor-entry workflow gate failed (${errors.length}):`);
  errors.forEach((error) => console.error(`- ${error}`));
  process.exit(1);
}
console.log(`Wash-supervisor-entry workflow gate passed: ${requests.length} requests, two Newman phases and deterministic rejected entry.`);
