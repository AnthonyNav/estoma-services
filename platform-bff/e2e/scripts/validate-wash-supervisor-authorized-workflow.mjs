#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Script,runInNewContext} from 'node:vm';
const dir=new URL('../',import.meta.url);
const read=p=>readFileSync(new URL(p,dir),'utf8');
const c=JSON.parse(read('postman/workflows/wash-supervisor-authorized-front-v1.postman_collection.json'));
const rejected=JSON.parse(read('postman/workflows/wash-supervisor-entry-front-v1.postman_collection.json'));
assert.deepEqual(c.item[0],rejected.item[0]);
const items=c.item.flatMap(x=>x.item);
assert.equal(items.length,17);
assert.equal(c.item[1].name,'Arrival and authorized entry');
for(const item of items) {
  const url=typeof item.request.url==='string'?item.request.url:item.request.url.raw;
  assert(url.startsWith('{{baseUrl}}/'));
  for(const event of item.event??[]) new Script(event.script.exec.join('\n'));
}
const decision=JSON.parse(items[13].request.body.raw.replace('{{washExecutionVersion}}','1'));
assert.deepEqual(decision,{identityConfirmed:true,requirementsSatisfied:true,decision:'AUTHORIZED',expectedVersion:1});
for(const index of [10,13]) assert(items[index].request.header.some(x=>x.key==='Idempotency-Key'&&x.value.startsWith('{{runKey}}-')));
const result=items[14].event[0].script.exec.join('\n');
assert(result.includes('status:"IN_PROGRESS",tankId:pm.environment.get("fixtureTankId")'));
const lookup=items[15].event[0].script.exec.join('\n');
for(const required of ['IN_PROGRESS','EXIT_REVIEW','fixtureCabinId','fixtureTankId',"assignmentType).to.eql('INITIAL')",'executionVersion).to.eql(2)','rejectionReason).to.eql(null)','maxReadAttempts']) assert(lookup.includes(required),required);
assert(!lookup.includes('ENTRY_REJECTED'));
// Execute the terminal assertions too: a wrong resource/version must fail.
const vars=new Map(Object.entries({fixtureStudentAccountId:'student',fixtureStudentEnrollment:'enrollment',appointmentId:'appointment',courseSectionId:'course',appointmentTimeSlotId:'slot',washExecutionId:'execution',fixtureCabinId:'cabin',fixtureTankId:'tank'}));
const body={serviceDate:'2026-09-05',student:{studentAccountId:'student',studentEnrollment:'enrollment'},appointment:{appointmentId:'appointment',appointmentStatus:'IN_PROGRESS',courseSectionReference:{courseSectionId:'course'},appointmentTimeSlot:{appointmentTimeSlotId:'slot'}},nextAction:'EXIT_REVIEW',washExecution:{washExecutionId:'execution',status:'IN_PROGRESS',executionVersion:2,rejectionReason:null,exitSubmittedAt:null},activeResourceAssignment:{resourceAssignmentId:'00000000-0000-4000-8000-000000000001',assignmentType:'INITIAL',cabin:{resourceId:'cabin'},tank:{resourceId:'tank'}}};
function expect(value) {
  const chain={eql:other=>assert.equal(JSON.stringify(value),JSON.stringify(other)),match:regex=>assert(regex.test(value)),below:other=>assert(value<other)};
  for(const k of ['to','be','deep']) chain[k]=chain;
  return chain;
}
function checkLookup(value) {
  runInNewContext(lookup,{pm:{response:{json:()=>value,to:{have:{status:code=>assert.equal(code,200)}}},test:(_name,fn)=>fn(),expect,environment:{get:k=>vars.get(k),set:()=>{}},execution:{setNextRequest:()=>{throw new Error('unexpected retry');}}}});
}
checkLookup(body);
for(const mutate of [b=>b.activeResourceAssignment.tank.resourceId='foreign',b=>b.activeResourceAssignment.cabin.resourceId='foreign',b=>b.washExecution.executionVersion=1,b=>b.activeResourceAssignment.assignmentType='REASSIGNMENT']) {
  const wrong=structuredClone(body); mutate(wrong); assert.throws(()=>checkLookup(wrong));
}
const runner=read('runner/run-wash-supervisor-entry.sh');
assert(runner.includes('"$E2E_PSQL_EXEC" --assert-isolated-wash'));
assert(runner.includes('entry_outcome=${E2E_ENTRY_OUTCOME:-rejected}'));
assert(runner.includes('fixtures/wash-supervisor-authorized/fixture-db.sh'));
assert(runner.includes('fixtureCabinId')&&runner.includes('fixtureTankId'));
console.log('Authorized-entry gate passed: 17 requests, exact decision/resource assertions, valid scripts and isolated runner gate.');
