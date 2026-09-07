#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Script,runInNewContext} from 'node:vm';

const root=new URL('../',import.meta.url);
const read=path=>readFileSync(new URL(path,root),'utf8');
const collection=JSON.parse(read('postman/workflows/wash-exit-completion-front-v1.postman_collection.json'));
const environment=JSON.parse(read('postman/workflows/wash-exit-completion-front-v1.environment.example.json'));
const generated=collection.item.at(-1);
assert.equal(generated.name,'Exit and completion contract (live runner gated)');
assert.equal(generated.item.length,21);

for(const item of collection.item.flatMap(group=>group.item)){
  const url=typeof item.request.url==='string'?item.request.url:item.request.url.raw;
  assert(url.startsWith('{{baseUrl}}/api/')||url.startsWith('{{baseUrl}}{{'),`${item.name}: private BFF URL required`);
  for(const event of item.event??[]) new Script(event.script.exec.join('\n'));
}

const byName=new Map(generated.item.map(item=>[item.name,item]));
const urlOf=name=>byName.get(name).request.url;
assert.equal(urlOf('23 Submit exit'),'{{baseUrl}}/api/v1/wash/executions/{{washExecutionId}}/exit');
assert.equal(urlOf('31 Complete execution'),'{{baseUrl}}/api/v1/wash/executions/{{washExecutionId}}/complete');
assert.equal(urlOf('34 Exact read converges to completed'),'{{baseUrl}}/api/v1/wash/supervision/executions/{{washExecutionId}}');
for(const name of ['23 Submit exit','24 Retry identical exit','31 Complete execution','32 Retry identical completion']){
  const header=byName.get(name).request.header.find(value=>value.key==='Idempotency-Key');
  assert(header?.value.startsWith('{{runKey}}-'),`${name}: scoped idempotency key required`);
}
assert.equal(byName.get('23 Submit exit').request.body.raw,byName.get('24 Retry identical exit').request.body.raw);
assert.equal(byName.get('31 Complete execution').request.body.raw,byName.get('32 Retry identical completion').request.body.raw);
assert.equal(byName.get('23 Submit exit').request.header.at(-1).value,byName.get('24 Retry identical exit').request.header.at(-1).value);
assert.equal(byName.get('31 Complete execution').request.header.at(-1).value,byName.get('32 Retry identical completion').request.header.at(-1).value);

const all=JSON.stringify(generated);
for(const required of ['REJECTED','VERSION_CONFLICT','hasOwnProperty.call','STUDENT_ENROLLMENT','EXIT_REVIEW','submittedExitMaterials','finalExitMaterials','lastResourceAssignment','completedVersion']) assert(all.includes(required),required);
for(const forbidden of ['QR','appointmentId}}/complete','data.aggregateId).to.eql(pm.environment.get']) assert(!all.includes(forbidden),forbidden);
const publicEnvironment=new Set(environment.values.map(value=>value.key));
for(const required of ['maxPollAttempts','maxReadAttempts','preExitVersion','exitVersion','completedVersion']) assert(publicEnvironment.has(required));
for(const value of environment.values) assert(!/(bearer|eyJ|password.+[^:]\s*[A-Za-z0-9]{8})/i.test(String(value.value)),`${value.key}: example contains secret-like data`);

function makeExpect(value){
  const chain={};
  chain.eql=other=>assert.equal(JSON.stringify(value),JSON.stringify(other));
  chain.above=other=>assert(value>other);
  chain.below=other=>assert(value<other);
  chain.match=regex=>assert(regex.test(value));
  chain.a=type=>assert.equal(typeof value,type);
  chain.empty=()=>assert.equal(value.length,0);
  for(const key of ['to','be','deep','and','not']) chain[key]=chain;
  return chain;
}
function execute(name,response,initial={},expectedNext=null){
  const values=new Map(Object.entries(initial));
  const failures=[];const stops=[];const nexts=[];
  const script=byName.get(name).event[0].script.exec.join('\n');
  const pm={response:{code:response.__status,json:()=>{if(response.__jsonError)throw new Error('synthetic parse error');return structuredClone(response);},to:{have:{status:code=>assert.equal(response.__status,code)}}},environment:{get:key=>values.get(key),set:(key,value)=>values.set(key,value),unset:key=>values.delete(key)},execution:{setNextRequest:next=>{if(next===null)stops.push(next);else{nexts.push(next);if(next!==expectedNext)failures.push(`unexpected retry ${next}`);}}},expect:makeExpect,test:(name,fn)=>{try{fn();}catch(error){failures.push(`${name}: ${error.message}`);}}};
  try{runInNewContext(script,{pm});}catch(error){error.workflowStops=stops;throw error;}
  assert.deepEqual(failures,[]);
  assert.deepEqual(nexts,expectedNext===null?[]:[expectedNext]);
  return values;
}
function assertStopped(fn){
  let caught;try{fn();}catch(error){caught=error;}
  assert(caught,'workflow should have stopped');
  assert.deepEqual(caught.workflowStops,[null],'failure must explicitly stop subsequent requests');
}

const uuid='00000000-0000-4000-8000-000000000010';
const ids={appointmentId:'00000000-0000-4000-8000-000000000011',washExecutionId:'00000000-0000-4000-8000-000000000012',resourceAssignmentId:'00000000-0000-4000-8000-000000000013',fixtureCabinId:'00000000-0000-4000-8000-000000000014',fixtureTankId:'00000000-0000-4000-8000-000000000015',exitVersion:'3',completedVersion:'4',maxReadAttempts:'2'};
const exact={__status:200,appointment:{appointmentId:ids.appointmentId,appointmentStatus:'COMPLETED'},washExecution:{washExecutionId:ids.washExecutionId,status:'COMPLETED',version:4,executionVersion:4,completedAt:'2026-09-05T12:00:00Z',activeResourceAssignment:null,lastResourceAssignment:{resourceAssignmentId:ids.resourceAssignmentId,cabin:{resourceId:ids.fixtureCabinId},tank:{resourceId:ids.fixtureTankId}},submittedExitMaterials:{packageCount:2,greenPaperCassette8Count:1,greenPaperCassette10Count:0,witnessTapePortionCount:2},finalExitMaterials:{packageCount:2,greenPaperCassette8Count:1,greenPaperCassette10Count:1,witnessTapePortionCount:2}}};
execute('34 Exact read converges to completed',exact,ids);
for(const mutate of [
  body=>body.washExecution.washExecutionId=uuid,
  body=>body.washExecution.submittedExitMaterials.packageCount=3,
  body=>body.washExecution.finalExitMaterials.greenPaperCassette10Count=0,
  body=>body.washExecution.executionVersion=3,
  body=>body.washExecution.lastResourceAssignment.tank.resourceId=uuid
]){const wrong=structuredClone(exact);mutate(wrong);assertStopped(()=>execute('34 Exact read converges to completed',wrong,ids));}

const retry={__status:202,operationId:uuid,pollPath:'/api/v1/operations/'+uuid,status:'PENDING',submittedAt:'2026-09-05T12:00:00Z'};
execute('24 Retry identical exit',retry,{exitOperationId:uuid,exitOperationIdPollPath:retry.pollPath,exitOperationIdSubmittedAt:retry.submittedAt});
assertStopped(()=>execute('24 Retry identical exit',{...retry,operationId:ids.washExecutionId},{exitOperationId:uuid,exitOperationIdPollPath:retry.pollPath,exitOperationIdSubmittedAt:retry.submittedAt}));
assertStopped(()=>execute('24 Retry identical exit',{...retry,submittedAt:'2026-09-05T12:00:01Z'},{exitOperationId:uuid,exitOperationIdPollPath:retry.pollPath,exitOperationIdSubmittedAt:retry.submittedAt}));
execute('29 Poll stale completion',{__status:200,operationId:uuid,status:'REJECTED',publicOperation:'wash-complete-execution',errorCode:'VERSION_CONFLICT',data:null,completedAt:'2026-09-05T12:00:00Z'},{staleCompleteOperationId:uuid,maxPollAttempts:'2'});
assertStopped(()=>execute('29 Poll stale completion',{__status:200,operationId:uuid,status:'REJECTED',publicOperation:'wash-complete-execution',errorCode:'VERSION_CONFLICT',data:{aggregateId:ids.washExecutionId},completedAt:'2026-09-05T12:00:00Z'},{staleCompleteOperationId:uuid,maxPollAttempts:'2'}));

const submittedExecution={washExecutionId:ids.washExecutionId,status:'EXIT_SUBMITTED',version:3,executionVersion:3,exitSubmittedAt:'2026-09-05T11:59:00Z',activeResourceAssignment:{resourceAssignmentId:ids.resourceAssignmentId,cabinId:ids.fixtureCabinId,tankId:ids.fixtureTankId},submittedExitMaterials:exact.washExecution.submittedExitMaterials,finalExitMaterials:null};
const submittedHome={__status:200,appointment:{appointmentId:ids.appointmentId,washExecution:submittedExecution}};
execute('26 Home converges to exit submitted',submittedHome,{...ids,preExitVersion:'2'});
const wrongResource=structuredClone(submittedHome);wrongResource.appointment.washExecution.activeResourceAssignment.tankId=uuid;
assertStopped(()=>execute('26 Home converges to exit submitted',wrongResource,{...ids,preExitVersion:'2'}));
const submittedExact={__status:200,appointment:{appointmentId:ids.appointmentId},washExecution:{...submittedExecution,activeResourceAssignment:{resourceAssignmentId:ids.resourceAssignmentId,assignmentType:'INITIAL',cabin:{resourceId:ids.fixtureCabinId},tank:{resourceId:ids.fixtureTankId}}}};
execute('30 Exact read unchanged after stale completion',submittedExact,ids);
const wrongSubmitted=structuredClone(submittedExact);wrongSubmitted.washExecution.submittedExitMaterials.packageCount=9;
assertStopped(()=>execute('30 Exact read unchanged after stale completion',wrongSubmitted,ids));
assertStopped(()=>execute('26 Home converges to exit submitted',{__status:500},{...ids,preExitVersion:'2'}));
execute('25 Poll exit operation',{__status:200,status:'PENDING'},{exitOperationId:uuid,maxPollAttempts:'3'},'25 Poll exit operation');
assertStopped(()=>execute('25 Poll exit operation',{__status:200,status:'PENDING'},{exitOperationId:uuid,maxPollAttempts:'1'}));
execute('34 Exact read converges to completed',{__status:503},{...ids,maxReadAttempts:'3'},'34 Exact read converges to completed');
assertStopped(()=>execute('34 Exact read converges to completed',{__status:503},{...ids,maxReadAttempts:'1'}));
for(const name of ['27 Lookup exit submitted by enrollment','30 Exact read unchanged after stale completion']) assertStopped(()=>execute(name,{__status:502,__jsonError:true},ids));

const runner=read('runner/run.sh');
assert(!runner.includes('wash-exit-completion'),'live suite must remain unavailable before terminal cleanup exists');
console.log('Exit/completion static gate passed: 21 requests, executable terminal/dedupe/conflict assertions, no live runner.');
