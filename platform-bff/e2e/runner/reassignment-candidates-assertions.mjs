import assert from 'node:assert/strict';

const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function assertPositive(body, expected) {
  assert.deepEqual(Object.keys(body).sort(),['candidates','executionVersion','recommendedCandidate','snapshotGeneratedAt','washExecutionId']);
  assert.equal(body.washExecutionId,expected.executionId);
  assert.equal(body.executionVersion,2);
  assert.equal(body.candidates?.length,1);
  assert.deepEqual(body.recommendedCandidate,body.candidates[0]);
  const candidate=body.candidates[0];
  assert.deepEqual(Object.keys(candidate).sort(),['availableCapacity','cabinCode','cabinId','cabinName','tankCode','tankId','tankName']);
  assert.equal(candidate.cabinId,expected.cabinId);
  assert.equal(candidate.tankId,expected.tankId);
  assert.equal(candidate.cabinCode,expected.cabinCode);
  assert.equal(candidate.tankCode,expected.tankCode);
  assert.equal(candidate.cabinName,'E2E Candidate Cabin');
  assert.equal(candidate.tankName,'E2E Candidate Tank');
  assert.equal(candidate.availableCapacity,2);
  assert(uuid.test(candidate.cabinId) && uuid.test(candidate.tankId));
  assertFresh(body.snapshotGeneratedAt);
}

export function assertEmpty(body, expected) {
  assert.deepEqual(Object.keys(body).sort(),['candidates','executionVersion','recommendedCandidate','snapshotGeneratedAt','washExecutionId']);
  assert.equal(body.washExecutionId,expected.executionId);
  assert.equal(body.executionVersion,2);
  assert.deepEqual(body.candidates,[]);
  assert.equal(body.recommendedCandidate,null);
  assertFresh(body.snapshotGeneratedAt);
}

function assertFresh(value) {
  const generated=Date.parse(value);
  assert(Number.isFinite(generated));
  const age=Date.now()-generated;
  assert(age>=-250 && age<11000,'snapshotGeneratedAt must describe the current owner freshness window');
}

export function evidenceReport(executionId,positive,empty,tankInactiveAt) {
  const item=(snapshot,count)=>({queryMessageId:snapshot.queryId,resultMessageId:snapshot.resultId,
    correlationId:snapshot.correlationId,candidateCount:count,snapshotGeneratedAt:snapshot.snapshotGeneratedAt});
  return {executionId,positive:item(positive,1),empty:item(empty,0),tankInactiveAt};
}
