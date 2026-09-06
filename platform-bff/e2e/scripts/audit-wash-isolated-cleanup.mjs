#!/usr/bin/env node
// This environment is single-run/exclusive. Never use this audit for shared data.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const executor=process.env.E2E_PSQL_EXEC;
assert(executor,'E2E_PSQL_EXEC required');
execFileSync(executor,['--assert-isolated-wash'],{timeout:30000});
const tables={
  authentication_db:['accounts','account_credentials','person_status_projection','sessions'],
  platform_bff_db:['account_presentation_read_model','person_presentation_read_model','client_operations','appointment_read_model','wash_execution_read_model','wash_resource_assignment_read_model','wash_cabin_read_model','wash_tank_read_model','session_revocations','appointment_availability_refresh','appointment_availability_read_model','reassignment_candidates_refresh','reassignment_candidates_refresh_history','reassignment_candidates_read_model','reassignment_candidate_read_model'],
  appointments_db:['appointments','wash_execution_evidence_projection','wash_execution_transition_projection','pending_wash_execution_events'],
  wash_execution_db:['wash_executions','entry_validations','resource_assignments','cabin_projection','tank_projection','appointment_projection','appointment_time_slot_projection','authorization_role_projection','authorization_access_projection'],
};
for(const [db,names] of Object.entries(tables)) {
  const sql='BEGIN READ ONLY;\n'+names.map(t=>`SELECT '${t}=' || count(*) FROM ${t};`).join('\n')+'\nROLLBACK;\n';
  const lines=execFileSync(executor,[db],{input:sql,encoding:'utf8',timeout:30000}).trim().split('\n');
  assert.deepEqual(lines,names.map(t=>t+'=0'),db+' has residual synthetic data');
  console.log(`${db}: ${names.length} fixture tables empty`);
}
