ALTER TABLE student_course_sections ADD COLUMN enrollment_id uuid;
UPDATE student_course_sections SET enrollment_id = gen_random_uuid() WHERE enrollment_id IS NULL;
ALTER TABLE student_course_sections ALTER COLUMN enrollment_id SET NOT NULL;
ALTER TABLE student_course_sections ADD CONSTRAINT uq_student_course_sections_enrollment_id UNIQUE (enrollment_id);

ALTER TABLE processed_messages ALTER COLUMN reconciliation_state DROP NOT NULL;
ALTER TABLE processed_messages ALTER COLUMN reconciliation_state DROP DEFAULT;
UPDATE processed_messages SET reconciliation_state = NULL WHERE processing_state = 'APPLIED';
ALTER TABLE processed_messages DROP CONSTRAINT IF EXISTS processed_messages_reconciliation_state_check;
ALTER TABLE processed_messages ADD CONSTRAINT processed_messages_reconciliation_state_check
  CHECK ((processing_state = 'DEFERRED' AND reconciliation_state IN ('EVENT_PENDING', 'PROJECTION_RECONCILIATION'))
      OR (processing_state = 'APPLIED' AND reconciliation_state IS NULL));
