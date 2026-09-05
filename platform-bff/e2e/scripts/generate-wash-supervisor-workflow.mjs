#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const workflowDir = resolve(root, 'e2e/postman/workflows');
const sourcePath = resolve(workflowDir, 'wash-student-front-v1.postman_collection.json');
const collectionPath = resolve(workflowDir, 'wash-supervisor-front-v1.postman_collection.json');
const environmentPath = resolve(workflowDir, 'wash-supervisor-front-v1.environment.example.json');
const check = process.argv.includes('--check');

const source = JSON.parse(readFileSync(sourcePath, 'utf8'));

function cloneStudent(index, name) {
  const original = source.item[index];
  return JSON.parse(JSON.stringify(original).split(original.name).join(name));
}

function bearer(token) {
  return { type: 'bearer', bearer: [{ key: 'token', value: `{{${token}}}`, type: 'string' }] };
}

function script(lines) {
  return [{ listen: 'test', script: { type: 'text/javascript', exec: lines } }];
}

function supervisorLogin() {
  return {
    name: '01 Login synthetic supervisor',
    request: {
      method: 'POST',
      header: [{ key: 'Content-Type', value: 'application/json' }],
      body: {
        mode: 'raw',
        raw: '{\n  "loginType": "USERNAME",\n  "loginIdentifier": "{{supervisorLoginIdentifier}}",\n  "password": "{{supervisorPassword}}",\n  "deviceName": "platform-bff-wash-supervisor-e2e"\n}',
      },
      url: '{{baseUrl}}/api/v1/auth/login',
    },
    event: script([
      "pm.test('supervisor login returns 200', () => pm.response.to.have.status(200));",
      'const body = pm.response.json();',
      "pm.test('supervisor session is NORMAL and complete', () => {",
      "  pm.expect(body.authState).to.eql('NORMAL');",
      "  pm.expect(body.accessToken).to.be.a('string').and.not.empty;",
      '  pm.expect(body.accountId).to.match(/^[0-9a-f-]{36}$/i);',
      '});',
      "pm.collectionVariables.set('supervisorAccessToken', body.accessToken);",
      "pm.collectionVariables.set('supervisorAccountId', body.accountId);",
      "pm.environment.set('runtimeSupervisorAccessToken', body.accessToken);",
    ]),
  };
}

function supervisorHome(name, phase) {
  const baseline = phase === 'baseline';
  const scheduled = phase === 'scheduled';
  const counter = scheduled ? 'scheduledSupervisorHomeAttempts' : 'cancelledSupervisorHomeAttempts';
  const expectedCancelled = phase === 'cancelled' ? '+ 1' : '';
  const lines = [
    "pm.test('supervisor home returns 200', () => pm.response.to.have.status(200));",
    'const body = pm.response.json();',
    "pm.test('home has a server-derived service date and non-negative counters', () => {",
    "  pm.expect(body.serviceDate).to.match(/^\\d{4}-\\d{2}-\\d{2}$/);",
    "  for (const value of Object.values(body.summary)) pm.expect(value).to.be.a('number').and.at.least(0);",
    "  pm.expect(body.pendingReassignmentsCount).to.be.a('number').and.at.least(0);",
    '});',
  ];
  if (baseline) {
    lines.push(
      "pm.collectionVariables.set('serviceDate', body.serviceDate);",
      "pm.collectionVariables.set('baselineRegistered', String(body.summary.registeredAppointments));",
      "pm.collectionVariables.set('baselineInProcess', String(body.summary.inProcessAppointments));",
      "pm.collectionVariables.set('baselineCompleted', String(body.summary.completedAppointments));",
      "pm.collectionVariables.set('baselineDenied', String(body.summary.deniedAppointments));",
      "pm.collectionVariables.set('baselineCancelled', String(body.summary.cancelledAppointments));",
      "pm.collectionVariables.set('baselinePendingReassignments', String(body.pendingReassignmentsCount));",
    );
  } else {
    lines.push(
      "const maxAttempts = Number(pm.environment.get('maxReadAttempts') || 20);",
      `const attempts = Number(pm.collectionVariables.get('${counter}') || 0);`,
      "const baselineRegistered = Number(pm.collectionVariables.get('baselineRegistered'));",
      "const baselineCancelled = Number(pm.collectionVariables.get('baselineCancelled'));",
      `const converged = body.serviceDate === pm.collectionVariables.get('serviceDate') && body.summary.registeredAppointments === baselineRegistered + 1 && body.summary.cancelledAppointments === baselineCancelled ${expectedCancelled};`,
      'if (!converged) {',
      `  pm.collectionVariables.set('${counter}', String(attempts + 1));`,
      `  pm.test('${phase} supervisor summary converges within the retry budget', () => pm.expect(attempts + 1).to.be.below(maxAttempts));`,
      `  if (attempts + 1 < maxAttempts) pm.execution.setNextRequest('${name}');`,
      '  else pm.execution.setNextRequest(null);',
      '} else {',
      "  pm.test('unrelated supervisor counters remain unchanged', () => {",
      "    pm.expect(body.summary.inProcessAppointments).to.eql(Number(pm.collectionVariables.get('baselineInProcess')));",
      "    pm.expect(body.summary.completedAppointments).to.eql(Number(pm.collectionVariables.get('baselineCompleted')));",
      "    pm.expect(body.summary.deniedAppointments).to.eql(Number(pm.collectionVariables.get('baselineDenied')));",
      "    pm.expect(body.pendingReassignmentsCount).to.eql(Number(pm.collectionVariables.get('baselinePendingReassignments')));",
      '  });',
      '}',
    );
  }
  return {
    name,
    request: { method: 'GET', auth: bearer('supervisorAccessToken'), url: '{{baseUrl}}/api/v1/wash/supervision/home' },
    event: script(lines),
  };
}

function enrollmentLookup(name, expectedStatus, expectedAction, repeat = false) {
  const lines = [
    "pm.test('enrollment lookup returns 200', () => pm.response.to.have.status(200));",
    'const body = pm.response.json();',
    "pm.test('lookup resolves only the isolated appointment', () => {",
    "  pm.expect(body.serviceDate).to.eql(pm.collectionVariables.get('serviceDate'));",
    `  pm.expect(body.nextAction).to.eql('${expectedAction}');`,
    "  pm.expect(body.student.studentAccountId).to.eql(pm.collectionVariables.get('accountId'));",
    "  pm.expect(body.student.studentEnrollment).to.eql(pm.environment.get('fixtureStudentEnrollment'));",
    "  pm.expect(body.appointment.appointmentId).to.eql(pm.collectionVariables.get('appointmentId'));",
    `  pm.expect(body.appointment.appointmentStatus).to.eql('${expectedStatus}');`,
    "  pm.expect(body.appointment.courseSectionReference.courseSectionId).to.eql(pm.collectionVariables.get('courseSectionId'));",
    "  pm.expect(body.appointment.appointmentTimeSlot.appointmentTimeSlotId).to.eql(pm.collectionVariables.get('appointmentTimeSlotId'));",
    '  pm.expect(body.washExecution).to.eql(null);',
    '  pm.expect(body.activeResourceAssignment).to.eql(null);',
    '});',
  ];
  if (repeat) {
    lines.push(
      "pm.test('repeated lookup is side-effect-free and stable', () => {",
      "  pm.expect(JSON.stringify(body)).to.eql(pm.collectionVariables.get('scheduledLookupFingerprint'));",
      '});',
    );
  } else if (expectedStatus === 'SCHEDULED') {
    lines.push("pm.collectionVariables.set('scheduledLookupFingerprint', JSON.stringify(body));");
  }
  return {
    name,
    request: {
      method: 'POST',
      auth: bearer('supervisorAccessToken'),
      header: [{ key: 'Content-Type', value: 'application/json' }],
      body: { mode: 'raw', raw: '{\n  "lookupType": "STUDENT_ENROLLMENT",\n  "studentEnrollment": "{{fixtureStudentEnrollment}}"\n}' },
      url: '{{baseUrl}}/api/v1/wash/supervision/lookup',
    },
    event: script(lines),
  };
}

function qrLookup() {
  return {
    name: '12 WASH-SUP-01 QR remains fail-closed',
    request: {
      method: 'POST',
      auth: bearer('supervisorAccessToken'),
      header: [{ key: 'Content-Type', value: 'application/json' }],
      body: { mode: 'raw', raw: '{\n  "lookupType": "QR",\n  "qrRepresentation": "e2e-opaque-unprojected"\n}' },
      url: '{{baseUrl}}/api/v1/wash/supervision/lookup',
    },
    event: script([
      "pm.test('QR lookup returns controlled 503', () => pm.response.to.have.status(503));",
      'const body = pm.response.json();',
      "pm.test('QR gap is reported as projection unavailable', () => {",
      "  pm.expect(pm.response.headers.get('Content-Type')).to.include('application/problem+json');",
      "  pm.expect(body.code).to.eql('BFF.PROJECTION_UNAVAILABLE');",
      "  pm.expect(body.status).to.eql(503);",
      '});',
    ]),
  };
}

function supervisorLogout() {
  return {
    name: '20 Logout synthetic supervisor',
    request: { method: 'POST', auth: bearer('supervisorAccessToken'), url: '{{baseUrl}}/api/v1/auth/logout' },
    event: script([
      "pm.test('supervisor logout returns 200', () => pm.response.to.have.status(200));",
      'const body = pm.response.json();',
      "pm.test('supervisor session is revoked', () => pm.expect(body.revoked).to.eql(true));",
      "if (body.revoked === true) pm.environment.unset('runtimeSupervisorAccessToken');",
    ]),
  };
}

const collection = {
  info: {
    name: 'platform-bff Front V1 - Wash Supervisor Home and Lookup',
    description: 'Ordered, mutating WASH-SUP-06 plus side-effect-free WASH-SUP-01 lookup workflow. A student schedules and cancels through the public BFF while a separate synthetic supervisor observes Home and lookup projection convergence.',
    schema: source.info.schema,
  },
  variable: [
    ...source.variable,
    { key: 'supervisorAccessToken', value: '' },
    { key: 'supervisorAccountId', value: '' },
    { key: 'serviceDate', value: '' },
    { key: 'baselineRegistered', value: '' },
    { key: 'baselineInProcess', value: '' },
    { key: 'baselineCompleted', value: '' },
    { key: 'baselineDenied', value: '' },
    { key: 'baselineCancelled', value: '' },
    { key: 'baselinePendingReassignments', value: '' },
    { key: 'scheduledSupervisorHomeAttempts', value: '0' },
    { key: 'cancelledSupervisorHomeAttempts', value: '0' },
    { key: 'scheduledLookupFingerprint', value: '' },
  ],
  item: [
    supervisorLogin(),
    supervisorHome('02 WASH-SUP-06 baseline home', 'baseline'),
    cloneStudent(0, '03 Login synthetic student'),
    cloneStudent(1, '04 WASH-STU-01 home without appointment'),
    cloneStudent(2, '05 WASH-STU-02 form context'),
    cloneStudent(3, '06 WASH-STU-02 availability cache-aside'),
    cloneStudent(4, '07 WASH-STU-02 schedule appointment'),
    cloneStudent(5, '08 Poll schedule operation'),
    cloneStudent(6, '09 WASH-STU-01 home converges to scheduled'),
    supervisorHome('10 WASH-SUP-06 home observes scheduled appointment', 'scheduled'),
    enrollmentLookup('11 WASH-SUP-01 lookup scheduled appointment', 'SCHEDULED', 'ENTRY'),
    qrLookup(),
    enrollmentLookup('13 Repeat WASH-SUP-01 enrollment lookup', 'SCHEDULED', 'ENTRY', true),
    cloneStudent(7, '14 WASH-STU-05 cancel appointment'),
    cloneStudent(8, '15 Poll cancel operation'),
    cloneStudent(9, '16 WASH-STU-01 home converges to cancelled'),
    enrollmentLookup('17 WASH-SUP-01 lookup converges to terminal appointment', 'CANCELLED', 'NONE'),
    supervisorHome('18 WASH-SUP-06 home observes cancellation', 'cancelled'),
    cloneStudent(10, '19 Logout synthetic student'),
    supervisorLogout(),
  ],
};

const environment = {
  name: 'platform-bff-wash-supervisor-front-v1-example',
  values: [
    { key: 'baseUrl', value: '', enabled: true },
    { key: 'loginIdentifier', value: '', enabled: true },
    { key: 'password', value: '', enabled: true },
    { key: 'runtimeAccessToken', value: '', enabled: true },
    { key: 'supervisorLoginIdentifier', value: '', enabled: true },
    { key: 'supervisorPassword', value: '', enabled: true },
    { key: 'runtimeSupervisorAccessToken', value: '', enabled: true },
    { key: 'runKey', value: '', enabled: true },
    { key: 'fixtureCourseSectionId', value: '', enabled: true },
    { key: 'fixtureAppointmentTimeSlotId', value: '', enabled: true },
    { key: 'fixtureStudentEnrollment', value: '', enabled: true },
    { key: 'maxPollAttempts', value: '30', enabled: true },
    { key: 'maxReadAttempts', value: '20', enabled: true },
  ],
  _postman_variable_scope: 'environment',
  _postman_exported_at: '2026-09-05T00:00:00.000Z',
  _postman_exported_using: 'Codex',
};

const outputs = [
  [collectionPath, `${JSON.stringify(collection, null, 2)}\n`],
  [environmentPath, `${JSON.stringify(environment, null, 2)}\n`],
];

if (check) {
  const stale = outputs.filter(([path, content]) => {
    try { return readFileSync(path, 'utf8') !== content; } catch { return true; }
  });
  if (stale.length) {
    stale.forEach(([path]) => console.error(`generated workflow is stale: ${path}`));
    process.exit(1);
  }
  console.log('Wash-supervisor generated workflow is current.');
} else {
  outputs.forEach(([path, content]) => writeFileSync(path, content));
  console.log('Generated wash-supervisor Postman collection and blank example environment.');
}
