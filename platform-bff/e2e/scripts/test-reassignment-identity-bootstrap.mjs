#!/usr/bin/env node
// Execute the real runner and identity helper; simulate only the SQL/hash boundary.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {existsSync,mkdtempSync,readFileSync,writeFileSync,rmSync,copyFileSync,chmodSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {basename,join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ownPath=fileURLToPath(import.meta.url);
const name=basename(ownPath);
if(name==='fake-hash') {
  readFileSync(0);
  process.stdout.write('$argon2id$test-only-placeholder');
} else if(name==='fake-psql') {
  const db=process.argv[2];
  if(db==='--assert-isolated-wash') process.exit(0);
  assert(['authentication_db','platform_bff_db'].includes(db),'unexpected database');
  const sql=readFileSync(0,'utf8');
  const state=JSON.parse(readFileSync(process.env.TEST_IDENTITY_STATE,'utf8'));
  if(sql.includes('INSERT INTO accounts(')) {
    assert.equal(db,'authentication_db');
    state.authCommitted=true;
    state.calls.push('auth-bootstrap-commit');
  } else if(sql.includes('INSERT INTO account_presentation_read_model(')) {
    assert.equal(db,'platform_bff_db');
    assert(state.authCommitted,'failure must follow the Authentication commit');
    state.calls.push('bff-bootstrap-failure');
    writeFileSync(process.env.TEST_IDENTITY_STATE,JSON.stringify(state));
    process.exit(23);
  } else if(sql.includes('string_agg')) {
    process.stdout.write('\n');
  } else if(sql.includes('DELETE FROM account_presentation_read_model')) {
    state.calls.push('bff-cleanup');
  } else if(sql.includes('DELETE FROM accounts WHERE')) {
    state.authCommitted=false;
    state.calls.push('auth-cleanup');
  } else if(sql.trimStart().startsWith('SELECT')) {
    // Force the existing identity helper's dependent-row guard in the second case.
    const blocked=process.env.TEST_IDENTITY_BLOCKED==='1' && sql.includes('password_recovery_requests');
    process.stdout.write(blocked?'1\n':'0\n');
    if(blocked) state.calls.push('cleanup-guard-refused');
  } else {
    throw new Error('unexpected SQL shape');
  }
  writeFileSync(process.env.TEST_IDENTITY_STATE,JSON.stringify(state));
} else {
  const root=mkdtempSync(join(tmpdir(),'candidate-identity-regression-'));
  try {
    const psql=join(root,'fake-psql'), hash=join(root,'fake-hash');
    for(const path of [psql,hash]) {copyFileSync(ownPath,path); chmodSync(path,0o700);}
    const runner=fileURLToPath(new URL('../runner/run-reassignment-candidates.mjs',import.meta.url));
    for(const blocked of [false,true]) {
      const report=join(root,blocked?'guarded':'recoverable');
      const statePath=join(root,blocked?'guarded-state.json':'recoverable-state.json');
      writeFileSync(statePath,JSON.stringify({authCommitted:false,calls:[]}),{mode:0o600});
      const result=spawnSync(process.execPath,[runner],{
        env:{...process.env,BFF_BASE_URL:'http://127.0.0.1:1',E2E_REPORT_DIR:report,
          E2E_PSQL_EXEC:psql,E2E_PASSWORD_HASH_EXEC:hash,TEST_IDENTITY_STATE:statePath,
          TEST_IDENTITY_BLOCKED:blocked?'1':'0'},encoding:'utf8',timeout:30000});
      assert(!result.error,`runner spawn failed: ${result.error?.code}`);
      assert.notEqual(result.status,0,'bootstrap failure must not become success');
      const state=JSON.parse(readFileSync(statePath,'utf8'));
      assert.deepEqual(state.calls,blocked
        ? ['auth-bootstrap-commit','bff-bootstrap-failure','cleanup-guard-refused']
        : ['auth-bootstrap-commit','bff-bootstrap-failure','bff-cleanup','auth-cleanup']);
      assert.equal(state.authCommitted,blocked);
      assert.equal(existsSync(join(report,'identity-fixture.env')),blocked);
      assert.equal(existsSync(join(report,'identity-credentials.json')),blocked);
      assert(!existsSync(join(report,'reassignment-candidates.env')));
    }
    console.log('Identity bootstrap interruption: real runner/helper recovery and guard preservation passed.');
  } finally {
    rmSync(root,{recursive:true,force:true});
  }
}
