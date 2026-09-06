ALTER TABLE wash_execution_read_model
  ADD COLUMN final_package_count INTEGER,
  ADD COLUMN final_green_paper_cassette_8_count INTEGER,
  ADD COLUMN final_green_paper_cassette_10_count INTEGER,
  ADD COLUMN final_witness_tape_portion_count INTEGER,
  ADD COLUMN final_exit_version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT wash_execution_final_materials_shape_ck CHECK (
    (
      final_exit_version = 0
      AND final_package_count IS NULL
      AND final_green_paper_cassette_8_count IS NULL
      AND final_green_paper_cassette_10_count IS NULL
      AND final_witness_tape_portion_count IS NULL
    )
    OR
    (
      final_exit_version > 0
      AND final_package_count IS NOT NULL AND final_package_count >= 0
      AND final_green_paper_cassette_8_count IS NOT NULL
      AND final_green_paper_cassette_8_count >= 0
      AND final_green_paper_cassette_10_count IS NOT NULL
      AND final_green_paper_cassette_10_count >= 0
      AND final_witness_tape_portion_count IS NOT NULL
      AND final_witness_tape_portion_count >= 0
      AND final_package_count::BIGINT + final_green_paper_cassette_8_count::BIGINT
          + final_green_paper_cassette_10_count::BIGINT
          + final_witness_tape_portion_count::BIGINT > 0
    )
  );
