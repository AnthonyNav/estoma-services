#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
const dir=new URL('../postman/workflows/',import.meta.url);
const collection=JSON.parse(readFileSync(new URL('wash-supervisor-entry-front-v1.postman_collection.json',dir)));
collection.info.name='platform-bff Front V1 - Wash Supervisor Authorized Entry';
collection.info.description='Isolated wash-e2e ONLY. Real BFF arrival and AUTHORIZED entry with exact synthetic cabin/tank assignment. Use the fixture-backed runner; never run against shared develop.';
const phase=collection.item[1];
assert.equal(phase.item.length,9);
phase.name='Arrival and authorized entry';
const decision=phase.item[5];
assert.equal(decision.name,'14 WASH-SUP-01 reject entry');
decision.name='14 WASH-SUP-01 authorize entry';
decision.request.body.raw='{\n  "identityConfirmed": true,\n  "requirementsSatisfied": true,\n  "decision": "AUTHORIZED",\n  "expectedVersion": {{washExecutionVersion}}\n}';
const poll=phase.item[6];
poll.name='15 Poll authorized entry operation';
poll.event[0].script.exec=poll.event[0].script.exec.map(line=>line
  .replaceAll('15 Poll rejected entry operation',poll.name)
  .replace('pm.expect(body.data).to.deep.eql({"status":"ENTRY_REJECTED"});',
    'pm.expect(body.data).to.deep.eql({status:"IN_PROGRESS",tankId:pm.environment.get("fixtureTankId")});'));
const lookup=phase.item[7];
const oldName=lookup.name;
lookup.name='16 WASH-SUP-01 lookup converges to authorized entry';
lookup.event[0].script.exec=lookup.event[0].script.exec.map(line=>line
  .replaceAll(oldName,lookup.name)
  .replaceAll('ENTRY_REJECTED','IN_PROGRESS')
  .replaceAll('rejected','authorized')
  .replace("pm.expect(body.nextAction).to.eql('NONE');","pm.expect(body.nextAction).to.eql('EXIT_REVIEW');")
  .replace("pm.expect(body.washExecution.rejectionReason).to.eql('E2E_REQUIREMENTS_NOT_SATISFIED');",'pm.expect(body.washExecution.rejectionReason).to.eql(null);')
  .replace('pm.expect(body.activeResourceAssignment).to.eql(null);',[
    "pm.expect(body.activeResourceAssignment.resourceAssignmentId).to.match(/^[0-9a-f-]{36}$/i);",
    "pm.expect(body.activeResourceAssignment.assignmentType).to.eql('INITIAL');",
    "pm.expect(body.activeResourceAssignment.cabin.resourceId).to.eql(pm.environment.get('fixtureCabinId'));",
    "pm.expect(body.activeResourceAssignment.tank.resourceId).to.eql(pm.environment.get('fixtureTankId'));",
    "pm.expect(body.washExecution.exitSubmittedAt).to.eql(null);",
  ].join('\n')));
const environment=JSON.parse(readFileSync(new URL('wash-supervisor-entry-front-v1.environment.example.json',dir)));
environment.name='platform-bff-wash-supervisor-authorized-front-v1-example';
environment.values.push(...['fixtureCabinId','fixtureTankId'].map(key=>({key,value:'',enabled:true})));
for(const [file,value] of [['wash-supervisor-authorized-front-v1.postman_collection.json',collection],['wash-supervisor-authorized-front-v1.environment.example.json',environment]]) {
  const target=new URL(file,dir),output=JSON.stringify(value,null,2)+'\n';
  if(process.argv.includes('--check')) assert.equal(readFileSync(target,'utf8'),output);
  else writeFileSync(target,output);
}
console.log('Authorized-entry collection is current.');
