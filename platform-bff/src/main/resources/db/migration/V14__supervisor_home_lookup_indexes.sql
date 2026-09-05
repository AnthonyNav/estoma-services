CREATE INDEX student_academic_profile_active_number_idx
    ON student_academic_profile_read_model(student_number, updated_at DESC, student_academic_profile_id)
    WHERE academic_status = 'ACTIVE';

CREATE INDEX appointment_time_slot_service_date_idx
    ON appointment_time_slot_read_model(service_date, appointment_time_slot_id);

CREATE INDEX appointment_student_updated_idx
    ON appointment_read_model(student_account_id, updated_at DESC, appointment_id);

CREATE INDEX appointment_slot_idx
    ON appointment_read_model(appointment_time_slot_id, appointment_id);

CREATE INDEX wash_execution_appointment_updated_idx
    ON wash_execution_read_model(appointment_id, updated_at DESC, wash_execution_id);

CREATE INDEX wash_resource_assignment_active_execution_idx
    ON wash_resource_assignment_read_model(
      wash_execution_id, assigned_at DESC NULLS LAST, resource_assignment_id)
    WHERE released_at IS NULL;
