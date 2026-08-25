ALTER TABLE wash_execution_read_model
  ADD COLUMN submitted_package_count INTEGER,
  ADD COLUMN submitted_green_paper_cassette_8_count INTEGER,
  ADD COLUMN submitted_green_paper_cassette_10_count INTEGER,
  ADD COLUMN submitted_witness_tape_portion_count INTEGER,
  ADD COLUMN submitted_exit_version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT wash_execution_submitted_materials_shape_ck CHECK (
    (
      submitted_exit_version = 0
      AND submitted_package_count IS NULL
      AND submitted_green_paper_cassette_8_count IS NULL
      AND submitted_green_paper_cassette_10_count IS NULL
      AND submitted_witness_tape_portion_count IS NULL
    )
    OR
    (
      submitted_exit_version > 0
      AND submitted_package_count >= 0
      AND submitted_green_paper_cassette_8_count >= 0
      AND submitted_green_paper_cassette_10_count >= 0
      AND submitted_witness_tape_portion_count >= 0
    )
  );
