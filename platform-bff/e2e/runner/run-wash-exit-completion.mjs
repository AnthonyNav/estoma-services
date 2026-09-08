#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync,statSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';

const report=process.env.E2E_REPORT_DIR;
assert(report,'E2E_REPORT_DIR with bootstrapped isolated identities is required');
const base=new URL(process.env.BFF_BASE_URL);
assert(['127.0.0.1','localhost','[::1]'].includes(base.hostname),'use a private localhost tunnel to the isolated BFF');
const credentials=resolve(report,'identity-credentials.json');
assert.equal(statSync(credentials).mode&0o777,0o600,'identity credentials must be mode 0600');
const values=JSON.parse(readFileSync(credentials,'utf8'));
for(const role of ['student','supervisor']) {
  assert(/^e2e-[a-z0-9-]+$/.test(values[role+'Login']),'requires synthetic login');
  assert(typeof values[role+'Password']==='string'&&values[role+'Password'].length>0,'password required');
}
const child=spawn(fileURLToPath(new URL('./run-wash-exit-completion.sh',import.meta.url)),[],{
  stdio:'inherit',env:{...process.env,E2E_ISOLATED_NAMESPACE:'wash-e2e',
    E2E_STUDENT_LOGIN_IDENTIFIER:values.studentLogin,E2E_STUDENT_PASSWORD:values.studentPassword,
    E2E_SUPERVISOR_LOGIN_IDENTIFIER:values.supervisorLogin,E2E_SUPERVISOR_PASSWORD:values.supervisorPassword}});
for(const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>child.kill(signal));
child.on('error',()=>{console.error('Could not start isolated exit/completion runner');process.exitCode=1;});
child.on('exit',code=>{process.exitCode=code??1;});
