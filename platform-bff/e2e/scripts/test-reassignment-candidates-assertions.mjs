#!/usr/bin/env node
import assert from 'node:assert/strict';
import {assertEmpty,assertPositive,evidenceReport} from '../runner/reassignment-candidates-assertions.mjs';

const ids={executionId:'00000000-0000-4000-8000-000000000001',cabinId:'00000000-0000-4000-8000-000000000002',
  tankId:'00000000-0000-4000-8000-000000000003',cabinCode:'E2E-CAND-test',tankCode:'E2E-TANK-test'};
const candidate={cabinId:ids.cabinId,cabinCode:ids.cabinCode,cabinName:'E2E Candidate Cabin',
  tankId:ids.tankId,tankCode:ids.tankCode,tankName:'E2E Candidate Tank',availableCapacity:2};
const at=new Date().toISOString();
assertPositive({washExecutionId:ids.executionId,executionVersion:2,snapshotGeneratedAt:at,recommendedCandidate:candidate,candidates:[candidate]},ids);
assertEmpty({washExecutionId:ids.executionId,executionVersion:2,snapshotGeneratedAt:at,recommendedCandidate:null,candidates:[]},ids);
assert.throws(()=>assertPositive({washExecutionId:ids.executionId,executionVersion:2,snapshotGeneratedAt:at,recommendedCandidate:candidate,candidates:[]},ids));
assert.throws(()=>assertEmpty({washExecutionId:ids.executionId,executionVersion:2,snapshotGeneratedAt:at,recommendedCandidate:candidate,candidates:[]},ids));
const snapshot={queryId:'00000000-0000-4000-8000-000000000004',resultId:'00000000-0000-4000-8000-000000000005',
  correlationId:'00000000-0000-4000-8000-000000000006',snapshotGeneratedAt:at};
assert.deepEqual(evidenceReport(ids.executionId,snapshot,{...snapshot,resultId:'00000000-0000-4000-8000-000000000007'},at),{
  executionId:ids.executionId,positive:{queryMessageId:snapshot.queryId,resultMessageId:snapshot.resultId,
    correlationId:snapshot.correlationId,candidateCount:1,snapshotGeneratedAt:at},
  empty:{queryMessageId:snapshot.queryId,resultMessageId:'00000000-0000-4000-8000-000000000007',
    correlationId:snapshot.correlationId,candidateCount:0,snapshotGeneratedAt:at},tankInactiveAt:at});
console.log('Reassignment candidates runner response assertions passed.');
