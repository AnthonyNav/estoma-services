-- PR2 recovery performs guarded FULL rebuilds only on local external projections.
-- Business-owned Authorization state and audit remain non-deletable by the runtime role.
GRANT DELETE ON person_status_projection,
  account_status_projection,
  academic_eligibility_projection TO ${runtime_role};
