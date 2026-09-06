#!/usr/bin/env node
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {chmodSync,existsSync,mkdirSync,readFileSync,statSync,writeFileSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';
import {assertEmpty,assertPositive,evidenceReport} from './reassignment-candidates-assertions.mjs';

const runnerDir=dirname(fileURLToPath(import.meta.url));
const e2eDir=resolve(runnerDir,'..');
const report=resolve(process.env.E2E_REPORT_DIR ?? resolve(e2eDir,'../target/e2e/reassignment-candidates'));
const identityFixture=resolve(e2eDir,'fixtures/isolated-identities/fixture-db.sh');
const candidateFixture=resolve(e2eDir,'fixtures/reassignment-candidates/fixture-db.sh');
const identityManifest=resolve(report,'identity-fixture.env');
const credentialsPath=resolve(report,'identity-credentials.json');
const candidateManifest=resolve(report,'reassignment-candidates.env');
const executor=process.env.E2E_PSQL_EXEC;
assert(process.env.BFF_BASE_URL,'BFF_BASE_URL is required');
const base=new URL(process.env.BFF_BASE_URL);
assert(executor,'E2E_PSQL_EXEC is required');
assert(process.env.E2E_PASSWORD_HASH_EXEC,'E2E_PASSWORD_HASH_EXEC is required');
assert(['127.0.0.1','localhost','[::1]'].includes(base.hostname),'use a localhost tunnel to isolated wash-e2e');
assert(!existsSync(identityManifest) && !existsSync(credentialsPath) && !existsSync(candidateManifest),
  'standalone runner requires an empty report directory');
mkdirSync(report,{recursive:true,mode:0o700}); chmodSync(report,0o700);

const fixtureEnv={...process.env,E2E_REPORT_DIR:report,E2E_IDENTITY_MANIFEST:identityManifest,
  E2E_IDENTITY_CREDENTIALS:credentialsPath,E2E_CANDIDATE_FIXTURE_MANIFEST:candidateManifest};
function fixture(file,mode) { execFileSync(file,[mode],{env:fixtureEnv,stdio:'inherit',timeout:120000}); }
function query(db,statement) {
  return execFileSync(executor,[db],{input:statement,encoding:'utf8',timeout:30000}).trim();
}
function manifest() {
  assert.equal(statSync(candidateManifest).mode&0o777,0o600);
  return Object.fromEntries(readFileSync(candidateManifest,'utf8').trim().split('\n').map(line=>line.split('=',2)));
}
async function waitForSnapshot(executionId,previousResultId=null,expectedCount,minGeneratedEpoch=0) {
  for(let attempt=0;attempt<45;attempt++) {
    const row=query(process.env.E2E_BFF_DATABASE ?? 'platform_bff_db',`
SELECT s.result_message_id::text || '|' || s.query_message_id::text || '|' || s.correlation_id::text || '|' ||
 extract(epoch FROM s.snapshot_generated_at)::text || '|' || count(c.wash_execution_id)::text
FROM reassignment_candidates_read_model s
LEFT JOIN reassignment_candidate_read_model c USING(wash_execution_id)
JOIN reassignment_candidates_refresh r USING(wash_execution_id)
JOIN inbox_messages i ON i.consumer_name='bff-reassignment-candidates-results'
 AND i.message_id=s.result_message_id AND i.causation_id=s.query_message_id
 AND i.correlation_id=s.correlation_id AND i.message_kind='RESULT'
 AND i.message_type='GetReassignmentCandidatesResult' AND i.producer='wash-execution'
WHERE s.wash_execution_id='${executionId}'::uuid AND s.execution_version=2
 AND r.status='APPLIED' AND r.result_message_id=s.result_message_id
 AND CURRENT_TIMESTAMP>=s.snapshot_generated_at AND CURRENT_TIMESTAMP<s.fresh_until
GROUP BY s.result_message_id,s.query_message_id,s.correlation_id,s.snapshot_generated_at;`);
    if(row) {
      const [resultId,queryId,correlationId,generated,count]=row.split('|');
      const owner=query(process.env.E2E_WASH_EXECUTION_DATABASE ?? 'wash_execution_db',`
SELECT count(*) FROM outbox_messages WHERE message_id='${resultId}'::uuid
 AND causation_id='${queryId}'::uuid AND correlation_id='${correlationId}'::uuid
 AND message_kind='RESULT' AND message_type='GetReassignmentCandidatesResult'
 AND producer='wash-execution' AND status='PUBLISHED';`);
      if(Number(count)===expectedCount && resultId!==previousResultId
          && Number(generated)>=minGeneratedEpoch && owner==='1') {
        return {resultId,queryId,correlationId,generatedEpoch:Number(generated),
          snapshotGeneratedAt:new Date(Number(generated)*1000).toISOString()};
      }
    }
    await delay(1000);
  }
  throw new Error(`timed out waiting for a fresh ${expectedCount}-candidate owner snapshot`);
}
async function getMatchingSnapshot(ids,previousResultId,expectedCount,minGeneratedEpoch,headers) {
  for(let attempt=0;attempt<5;attempt++) {
    const snapshot=await waitForSnapshot(ids.EXECUTION_ID,previousResultId,expectedCount,minGeneratedEpoch);
    const publicResult=await request(`/api/v1/wash/supervision/pending-reassignments/${ids.EXECUTION_ID}/candidates`,{headers});
    if(publicResult.response.status===200
        && Date.parse(publicResult.body?.snapshotGeneratedAt)===Date.parse(snapshot.snapshotGeneratedAt)) {
      return {snapshot,publicResult};
    }
  }
  throw new Error('could not correlate HTTP response to the accepted owner snapshot');
}
async function request(path,options={}) {
  const response=await fetch(new URL(path,base),{...options,signal:AbortSignal.timeout(30000)});
  const text=await response.text();
  let body; try { body=text?JSON.parse(text):null; } catch { throw new Error(`HTTP ${response.status} returned non-JSON`); }
  return {response,body};
}

let token=''; let identityReady=false; let cleanupError=null;
try {
  execFileSync(executor,['--assert-isolated-wash'],{stdio:'inherit',timeout:30000});
  fixture(identityFixture,'bootstrap'); identityReady=true;
  fixture(candidateFixture,'bootstrap');
  const ids=manifest();
  const credentials=JSON.parse(readFileSync(credentialsPath,'utf8'));
  const login=await request('/api/v1/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({
    loginType:'USERNAME',loginIdentifier:credentials.supervisorLogin,password:credentials.supervisorPassword,
    deviceName:'e2e-reassignment-candidates'})});
  assert.equal(login.response.status,200,'synthetic supervisor login failed');
  assert.equal(login.body.accountId,ids.SUPERVISOR_ACCOUNT_ID);
  assert(typeof login.body.accessToken==='string' && login.body.accessToken.length>0);
  token=login.body.accessToken;
  const headers={authorization:`Bearer ${token}`};

  const positiveMatch=await getMatchingSnapshot(ids,null,1,0,headers);
  const positiveSnapshot=positiveMatch.snapshot; const positive=positiveMatch.publicResult;
  assert.equal(positive.response.status,200,'fresh positive snapshot was not publicly available');
  assertPositive(positive.body,{executionId:ids.EXECUTION_ID,cabinId:ids.CABIN_ID,tankId:ids.TANK_ID,
    cabinCode:ids.CABIN_CODE,tankCode:ids.TANK_CODE});

  fixture(candidateFixture,'deactivate-tank');
  const tankInactiveEpoch=Number(query(process.env.E2E_WASH_EXECUTION_DATABASE ?? 'wash_execution_db',
    `SELECT extract(epoch FROM updated_at)::text FROM tank_projection WHERE tank_id='${ids.TANK_ID}'::uuid AND status='INACTIVE' AND source_version=2;`));
  assert(Number.isFinite(tankInactiveEpoch));
  const emptyMatch=await getMatchingSnapshot(ids,positiveSnapshot.resultId,0,tankInactiveEpoch,headers);
  const emptySnapshot=emptyMatch.snapshot; const empty=emptyMatch.publicResult;
  assert.notEqual(emptySnapshot.queryId,positiveSnapshot.queryId,'empty proof must use a new owner Query');
  assert.equal(empty.response.status,200,'fresh empty snapshot was not publicly available');
  assertEmpty(empty.body,{executionId:ids.EXECUTION_ID});
  const evidence=evidenceReport(ids.EXECUTION_ID,
    {...positiveSnapshot,snapshotGeneratedAt:positive.body.snapshotGeneratedAt},
    {...emptySnapshot,snapshotGeneratedAt:empty.body.snapshotGeneratedAt},
    new Date(tankInactiveEpoch*1000).toISOString());
  const evidencePath=resolve(report,'reassignment-candidates-evidence.json');
  writeFileSync(evidencePath,JSON.stringify(evidence,null,2)+'\n',{mode:0o600}); chmodSync(evidencePath,0o600);
  console.log('Reassignment candidates real Query/Result positive and post-transition empty checks passed.');
} finally {
  if(token) {
    try { await request('/api/v1/auth/logout',{method:'POST',headers:{authorization:`Bearer ${token}`}}); } catch {}
    token='';
  }
  if(existsSync(candidateManifest)) {
    try { fixture(candidateFixture,'cleanup'); } catch(error) { cleanupError=error; }
  }
  if(identityReady && !existsSync(candidateManifest)) {
    try { fixture(identityFixture,'cleanup'); identityReady=false; } catch(error) { cleanupError??=error; }
  }
  if(cleanupError) throw cleanupError;
}
