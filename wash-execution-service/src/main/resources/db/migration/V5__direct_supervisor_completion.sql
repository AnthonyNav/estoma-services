ALTER TABLE wash_executions
  DROP CONSTRAINT ck_wash_executions_exit_shape;

ALTER TABLE wash_executions
  ADD CONSTRAINT ck_wash_executions_exit_shape CHECK (
    (
      status = 'EXIT_SUBMITTED'
      AND exit_submitted_by_account_id IS NOT NULL
      AND exit_submitted_at IS NOT NULL
      AND package_count IS NOT NULL
      AND green_paper_cassette_8_count IS NOT NULL
      AND green_paper_cassette_10_count IS NOT NULL
      AND witness_tape_portion_count IS NOT NULL
      AND (package_count > 0 OR green_paper_cassette_8_count > 0
        OR green_paper_cassette_10_count > 0 OR witness_tape_portion_count > 0)
    )
    OR (
      status = 'COMPLETED'
      AND (
        (exit_submitted_by_account_id IS NULL AND exit_submitted_at IS NULL)
        OR (exit_submitted_by_account_id IS NOT NULL AND exit_submitted_at IS NOT NULL)
      )
      AND package_count IS NOT NULL
      AND green_paper_cassette_8_count IS NOT NULL
      AND green_paper_cassette_10_count IS NOT NULL
      AND witness_tape_portion_count IS NOT NULL
      AND (package_count > 0 OR green_paper_cassette_8_count > 0
        OR green_paper_cassette_10_count > 0 OR witness_tape_portion_count > 0)
    )
    OR (
      status NOT IN ('EXIT_SUBMITTED', 'COMPLETED')
      AND exit_submitted_by_account_id IS NULL
      AND exit_submitted_at IS NULL
      AND package_count IS NULL
      AND green_paper_cassette_8_count IS NULL
      AND green_paper_cassette_10_count IS NULL
      AND witness_tape_portion_count IS NULL
    )
  );

ALTER TABLE wash_executions
  ADD CONSTRAINT ck_wash_executions_direct_completion_time CHECK (
    status <> 'COMPLETED'
    OR exit_submitted_at IS NOT NULL
    OR (started_at IS NOT NULL AND completed_at >= started_at)
  );
