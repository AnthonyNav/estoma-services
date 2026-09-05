#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const workflowDir = resolve(root, 'e2e/postman/workflows');
const sourcePath = resolve(workflowDir, 'wash-student-front-v1.postman_collection.json');
const collectionPath = resolve(workflowDir, 'wash-supervisor-entry-front-v1.postman_collection.json');
const environmentPath = resolve(workflowDir, 'wash-supervisor-entry-front-v1.environment.example.json');
const check = process.argv.includes('--check');
const source = JSON.parse(readFileSync(sourcePath, 'utf8'));

function cloneStudent(index, name) {
  const original = source.item[index];
  const renamed = JSON.stringify(original).split(original.name).join(name);
  return JSON.parse(renamed.replaceAll('pm.collectionVariables', 'pm.environment'));
}

function bearer(token) {
  return { type: 'bearer', bearer: [{ key: 'token', value: `{{${token}}}`, type: 'string' }] };
}

function script(lines) {
  return [{ listen: 'test', script: { type: 'text/javascript', exec: lines } }];
}

function supervisorLogin() {
  return {
    name: '09 Login synthetic supervisor',
    request: {
      method: 'POST',
      header: [{ key: 'Content-Type', value: 'application/json' }],
      body: {
        mode: 'raw',
        raw: '{\n  "loginType": "USERNAME",\n  "loginIdentifier": "{{supervisorLoginIdentifier}}",\n  "password": "{{supervisorPassword}}",\n  "deviceName": "platform-bff-wash-supervisor-entry-e2e"\n}',
      },
      url: '{{baseUrl}}/api/v1/auth/login',
    },
    event: script([
      "pm.test('supervisor login returns 200', () => pm.response.to.have.status(200));",
      'const body = pm.response.json();',
      "pm.test('supervisor session matches the isolated fixture', () => {",
      "  pm.expect(body.authState).to.eql('NORMAL');",
      "  pm.expect(body.accessToken).to.be.a('string').and.not.empty;",
      "  pm.expect(body.accountId).to.eql(pm.environment.get('fixtureSupervisorAccountId'));",
      '});',
      "pm.environment.set('supervisorAccessToken', body.accessToken);",
      "pm.environment.set('runtimeSupervisorAccessToken', body.accessToken);",
    ]),
  };
}

function lookup(name, phase) {
  const pending = phase === 'pending';
  const rejected = phase === 'rejected';
  const counter = pending ? 'pendingEntryLookupAttempts' : 'rejectedLookupAttempts';
  const lines = [
    "pm.test('enrollment lookup returns 200', () => pm.response.to.have.status(200));",
    'const body = pm.response.json();',
  ];
  if (pending || rejected) {
    const status = pending ? 'PENDING_ENTRY' : 'ENTRY_REJECTED';
    lines.push(
      "const execution = body.washExecution;",
      `const converged = body.appointment?.appointmentStatus === '${pending ? 'SCHEDULED' : status}' && execution?.status === '${status}';`,
      "const maxAttempts = Number(pm.environment.get('maxReadAttempts') || 20);",
      `const attempts = Number(pm.environment.get('${counter}') || 0);`,
      'if (!converged) {',
      `  pm.environment.set('${counter}', String(attempts + 1));`,
      `  pm.test('${phase} lookup converges within the retry budget', () => pm.expect(attempts + 1).to.be.below(maxAttempts));`,
      `  if (attempts + 1 < maxAttempts) pm.execution.setNextRequest('${name}');`,
      '  else pm.execution.setNextRequest(null);',
      '} else {',
    );
  }
  lines.push(
    `pm.test('${phase} lookup preserves the isolated context', () => {`,
    "  pm.expect(body.serviceDate).to.match(/^\\d{4}-\\d{2}-\\d{2}$/);",
    "  pm.expect(body.student.studentAccountId).to.eql(pm.environment.get('fixtureStudentAccountId'));",
    "  pm.expect(body.student.studentEnrollment).to.eql(pm.environment.get('fixtureStudentEnrollment'));",
    "  pm.expect(body.appointment.appointmentId).to.eql(pm.environment.get('appointmentId'));",
    "  pm.expect(body.appointment.courseSectionReference.courseSectionId).to.eql(pm.environment.get('courseSectionId'));",
    "  pm.expect(body.appointment.appointmentTimeSlot.appointmentTimeSlotId).to.eql(pm.environment.get('appointmentTimeSlotId'));",
  );
  if (phase === 'scheduled') {
    lines.push(
      "  pm.expect(body.appointment.appointmentStatus).to.eql('SCHEDULED');",
      "  pm.expect(body.nextAction).to.eql('ENTRY');",
      '  pm.expect(body.washExecution).to.eql(null);',
      '  pm.expect(body.activeResourceAssignment).to.eql(null);',
    );
  } else if (pending) {
    lines.push(
      "  pm.expect(body.appointment.appointmentStatus).to.eql('SCHEDULED');",
      "  pm.expect(body.nextAction).to.eql('ENTRY_DECISION');",
      "  pm.expect(body.washExecution.washExecutionId).to.match(/^[0-9a-f-]{36}$/i);",
      "  pm.expect(body.washExecution.status).to.eql('PENDING_ENTRY');",
      '  pm.expect(body.washExecution.executionVersion).to.eql(1);',
      "  pm.expect(body.washExecution.arrivedAt).to.be.a('string').and.not.empty;",
      '  pm.expect(body.washExecution.rejectionReason).to.eql(null);',
      '  pm.expect(body.activeResourceAssignment).to.eql(null);',
    );
  } else {
    lines.push(
      "  pm.expect(body.appointment.appointmentStatus).to.eql('ENTRY_REJECTED');",
      "  pm.expect(body.nextAction).to.eql('NONE');",
      "  pm.expect(body.washExecution.washExecutionId).to.eql(pm.environment.get('washExecutionId'));",
      "  pm.expect(body.washExecution.status).to.eql('ENTRY_REJECTED');",
      '  pm.expect(body.washExecution.executionVersion).to.eql(2);',
      "  pm.expect(body.washExecution.rejectionReason).to.eql('E2E_REQUIREMENTS_NOT_SATISFIED');",
      '  pm.expect(body.activeResourceAssignment).to.eql(null);',
    );
  }
  lines.push('});');
  if (pending) {
    lines.push(
      "pm.environment.set('washExecutionId', body.washExecution.washExecutionId);",
      "pm.environment.set('washExecutionVersion', String(body.washExecution.executionVersion));",
    );
  }
  if (pending || rejected) lines.push('}');
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

function acceptedMutation(name, path, keySuffix, body, operationVariable) {
  return {
    name,
    request: {
      method: 'POST',
      auth: bearer('supervisorAccessToken'),
      header: [
        { key: 'Content-Type', value: 'application/json' },
        { key: 'Idempotency-Key', value: `{{runKey}}-${keySuffix}` },
      ],
      body: { mode: 'raw', raw: body },
      url: `{{baseUrl}}${path}`,
    },
    event: script([
      "pm.test('command is durably accepted', () => pm.response.to.have.status(202));",
      'const body = pm.response.json();',
      "pm.test('command begins as a pollable operation', () => {",
      "  pm.expect(body.status).to.eql('PENDING');",
      "  pm.expect(body.operationId).to.match(/^[0-9a-f-]{36}$/i);",
      "  pm.expect(body.pollPath).to.eql('/api/v1/operations/' + body.operationId);",
      '});',
      `pm.environment.set('${operationVariable}', body.operationId);`,
    ]),
  };
}

function poll(name, operationVariable, counter, publicOperation, expectedData) {
  return {
    name,
    request: {
      method: 'GET',
      auth: bearer('supervisorAccessToken'),
      url: `{{baseUrl}}/api/v1/operations/{{${operationVariable}}}`,
    },
    event: script([
      "pm.test('operation is visible', () => pm.response.to.have.status(200));",
      'const body = pm.response.json();',
      "const maxAttempts = Number(pm.environment.get('maxPollAttempts') || 30);",
      `const attempts = Number(pm.environment.get('${counter}') || 0);`,
      "if (body.status === 'PENDING') {",
      `  pm.environment.set('${counter}', String(attempts + 1));`,
      `  pm.test('operation completes within the polling budget', () => pm.expect(attempts + 1).to.be.below(maxAttempts));`,
      `  if (attempts + 1 < maxAttempts) pm.execution.setNextRequest('${name}');`,
      '  else pm.execution.setNextRequest(null);',
      '} else {',
      "  pm.test('owner command succeeds', () => {",
      "    pm.expect(body.status, body.errorCode || 'no error code').to.eql('SUCCEEDED');",
      `    pm.expect(body.publicOperation).to.eql('${publicOperation}');`,
      '    pm.expect(body.errorCode).to.eql(null);',
      `    pm.expect(body.data).to.deep.eql(${JSON.stringify(expectedData)});`,
      "    pm.expect(body.completedAt).to.be.a('string').and.not.empty;",
      '  });',
      "  if (body.status !== 'SUCCEEDED') pm.execution.setNextRequest(null);",
      '}',
    ]),
  };
}

function supervisorLogout() {
  return {
    name: '17 Logout synthetic supervisor',
    request: { method: 'POST', auth: bearer('supervisorAccessToken'), url: '{{baseUrl}}/api/v1/auth/logout' },
    event: script([
      "pm.test('supervisor logout returns 200', () => pm.response.to.have.status(200));",
      'const body = pm.response.json();',
      "pm.test('supervisor session is revoked', () => pm.expect(body.revoked).to.eql(true));",
      "if (body.revoked === true) pm.environment.unset('runtimeSupervisorAccessToken');",
    ]),
  };
}

const studentLogin = cloneStudent(0, '01 Login synthetic student');
studentLogin.event[0].script.exec.splice(3, 0,
  "  pm.expect(body.accountId).to.eql(pm.environment.get('fixtureStudentAccountId')); ");

const collection = {
  info: {
    name: 'platform-bff Front V1 - Wash Supervisor Arrival and Rejected Entry',
    description: 'Two-phase, fixture-backed WASH-SUP-01 workflow. It schedules through the public student path, waits for the owner projection, registers arrival and records the deterministic REJECTED entry branch without touching shared wash resources.',
    schema: source.info.schema,
  },
  item: [
    {
      name: 'Prepare appointment',
      item: [
        studentLogin,
        cloneStudent(1, '02 WASH-STU-01 home without appointment'),
        cloneStudent(2, '03 WASH-STU-02 form context'),
        cloneStudent(3, '04 WASH-STU-02 availability cache-aside'),
        cloneStudent(4, '05 WASH-STU-02 schedule appointment'),
        cloneStudent(5, '06 Poll schedule operation'),
        cloneStudent(6, '07 WASH-STU-01 home converges to scheduled'),
        cloneStudent(10, '08 Logout synthetic student'),
      ],
    },
    {
      name: 'Arrival and rejected entry',
      item: [
        supervisorLogin(),
        lookup('10 WASH-SUP-01 lookup scheduled appointment', 'scheduled'),
        acceptedMutation('11 WASH-SUP-01 register arrival', '/api/v1/wash/executions/arrivals',
          'register-arrival', '{\n  "appointmentId": "{{appointmentId}}"\n}', 'arrivalOperationId'),
        poll('12 Poll arrival operation', 'arrivalOperationId', 'arrivalPollAttempts',
          'wash-register-arrival', { status: 'PENDING_ENTRY' }),
        lookup('13 WASH-SUP-01 lookup converges to pending entry', 'pending'),
        acceptedMutation('14 WASH-SUP-01 reject entry',
          '/api/v1/wash/executions/{{washExecutionId}}/entry-decision', 'decide-entry',
          '{\n  "identityConfirmed": true,\n  "requirementsSatisfied": false,\n  "decision": "REJECTED",\n  "rejectionReason": "E2E_REQUIREMENTS_NOT_SATISFIED",\n  "expectedVersion": {{washExecutionVersion}}\n}',
          'entryDecisionOperationId'),
        poll('15 Poll rejected entry operation', 'entryDecisionOperationId', 'entryDecisionPollAttempts',
          'wash-decide-entry', { status: 'ENTRY_REJECTED' }),
        lookup('16 WASH-SUP-01 lookup converges to rejected entry', 'rejected'),
        supervisorLogout(),
      ],
    },
  ],
};

const environment = {
  name: 'platform-bff-wash-supervisor-entry-front-v1-example',
  values: [
    { key: 'baseUrl', value: '', enabled: true },
    { key: 'loginIdentifier', value: '', enabled: true },
    { key: 'password', value: '', enabled: true },
    { key: 'runtimeAccessToken', value: '', enabled: true },
    { key: 'supervisorLoginIdentifier', value: '', enabled: true },
    { key: 'supervisorPassword', value: '', enabled: true },
    { key: 'runtimeSupervisorAccessToken', value: '', enabled: true },
    { key: 'fixtureStudentAccountId', value: '', enabled: true },
    { key: 'fixtureSupervisorAccountId', value: '', enabled: true },
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
  console.log('Wash-supervisor-entry generated workflow is current.');
} else {
  outputs.forEach(([path, content]) => writeFileSync(path, content));
  console.log('Generated wash-supervisor-entry Postman collection and blank example environment.');
}
