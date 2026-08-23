#!/usr/bin/env python3
import argparse
import uuid
from datetime import timedelta
from zoneinfo import ZoneInfo

import run


def choose_safe_zone():
    now = run.utcnow()
    for name in (
        "America/Mexico_City", "Pacific/Honolulu", "America/Los_Angeles",
        "UTC", "Europe/London", "Asia/Tokyo", "Pacific/Auckland",
    ):
        zone = ZoneInfo(name)
        if 8 <= now.astimezone(zone).hour <= 14:
            return zone
    return ZoneInfo("UTC")


run.choose_zone = choose_safe_zone


def seed_academic_single_period(self, student_id):
    now = run.utcnow()
    period_id = self.ids.get("academic_period")
    if period_id is None:
        period_id = run.uid()
        self.ids["academic_period"] = period_id
        self.event("academic", "academic.academic-period.activated", "AcademicPeriodActivated", {
            "academicPeriodId": period_id, "code": "E2E-2026", "status": "ACTIVE",
        }, period_id, 1, now)
    profile = run.uid()
    section = run.uid()
    self.ids[f"section:{student_id}"] = section
    self.event("academic", "academic.student-profile.created", "StudentAcademicProfileCreated", {
        "studentAcademicProfileId": profile, "accountId": student_id,
        "currentSemester": 5, "academicStatus": "ACTIVE",
    }, profile, 1, now)
    self.event("academic", "academic.course-section.registered", "CourseSectionRegistered", {
        "courseSectionId": section, "academicPeriodId": period_id, "status": "ACTIVE",
    }, section, 1, now)
    self.event("academic", "academic.student-course-section.enrolled", "StudentEnrolledInCourseSection", {
        "studentAcademicProfileId": profile, "accountId": student_id,
        "courseSectionId": section, "academicPeriodId": period_id, "enrolledAt": run.iso(now),
    }, section, 1, now)
    run.wait_until(f"academic enrollment {student_id}", lambda: run.scalar(
        run.APPOINTMENTS_DSN,
        "SELECT count(*) FROM student_course_section_projection WHERE student_account_id=%s",
        (uuid.UUID(student_id),)) == 1)


run.Harness.seed_academic = seed_academic_single_period


def cancel_scheduled_immuno(self):
    for appointment_id, student_id, version in run.rows(
        run.APPOINTMENTS_DSN,
        "SELECT appointment_id,student_account_id,version FROM appointments "
        "WHERE appointment_type='IMMUNOCOMPROMISED' AND status='SCHEDULED'"):
        now = run.utcnow()
        self.command("appointments", "CancelAppointment", {
            "appointmentId": str(appointment_id), "expectedVersion": int(version),
            "reason": "PR4_E2E_CLEANUP",
        }, self.account_actor(str(student_id)), occurred_at=now)
        run.wait_until("cleanup appointment cancellation", lambda aid=appointment_id: run.scalar(
            run.APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s", (aid,)) == "CANCELLED")


def finalize_in_progress(self):
    values = run.rows(run.EXECUTION_DSN,
        "SELECT wash_execution_id,appointment_id FROM wash_executions WHERE status='IN_PROGRESS'")
    for execution_id, appointment_id in values:
        student_id = run.scalar(run.APPOINTMENTS_DSN,
            "SELECT student_account_id FROM appointments WHERE appointment_id=%s", (appointment_id,))
        if student_id is not None:
            self.submit_and_complete(str(student_id), self.ids["supervisor"], str(execution_id))


def expire_open_cleanings(self):
    periods = run.rows(run.EXECUTION_DSN,
        "SELECT cleaning_period_id FROM cleaning_periods WHERE completed_at IS NULL")
    for (period_id,) in periods:
        run.execute(run.EXECUTION_DSN,
            "UPDATE cleaning_periods SET starts_at=now()-interval '4 hours', "
            "expected_ends_at=now()-interval '1 hour', completed_at=NULL, updated_at=now() "
            "WHERE cleaning_period_id=%s", (period_id,))
        run.wait_until("cleanup cleaning completion", lambda pid=period_id: run.scalar(
            run.EXECUTION_DSN, "SELECT completed_at IS NOT NULL FROM cleaning_periods WHERE cleaning_period_id=%s",
            (pid,)) is True, timeout=20)


def late_decision_after_valid_arrival(self, supervisor):
    print("[P1] valid +9m arrival blocks MISSED before supervisor decides at +12m")
    student = run.uid()
    self.seed_identity(student, "ALUMNO", academic=True)
    arrival = run.utcnow()
    start = arrival - timedelta(minutes=9)
    command_now = start - timedelta(minutes=30)
    slot = self.create_slot(start, command_now)
    appointment = self.schedule(student, slot, "NORMAL", command_now)
    execution = self.register_arrival(supervisor, appointment, slot, arrival)

    expected = int(run.scalar(run.APPOINTMENTS_DSN,
        "SELECT version FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)))
    missed = self.command("appointments", "MarkAppointmentAsMissed", {
        "appointmentId": appointment, "expectedVersion": expected,
    }, self.system_actor("appointments"), occurred_at=start + timedelta(minutes=11), producer="appointments")
    run.wait_until("missed command processed", lambda: run.scalar(
        run.APPOINTMENTS_DSN,
        "SELECT status FROM processed_messages WHERE message_id=%s", (uuid.UUID(missed["messageId"]),)) == "APPLIED")
    assert run.scalar(run.APPOINTMENTS_DSN,
        "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "SCHEDULED"

    self.decide(supervisor, execution, decided_at=start + timedelta(minutes=12))
    run.wait_until("late supervisor decision applied", lambda: run.scalar(
        run.APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s",
        (uuid.UUID(appointment),)) == "IN_PROGRESS")
    entry = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT entry_authorized_at FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),))
    assert entry is not None
    self.submit_and_complete(student, supervisor, execution)


def pending_initial_without_capacity_loss(self, supervisor):
    print("[07] authorized without local resource -> pending -> INITIAL")
    student = run.uid(); self.seed_identity(student, "ALUMNO", academic=True)
    now = run.utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now)
    appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
    # Fixture only: make the local Execution projection unusable without publishing an owner capacity-loss Event.
    run.execute(run.EXECUTION_DSN, "UPDATE tank_projection SET status='INACTIVE' WHERE tank_id=%s",
                (uuid.UUID(self.ids["tank_immuno"]),))
    execution = self.register_arrival(supervisor, appointment, slot, now)
    self.decide(supervisor, execution)
    run.wait_until("pending initial", lambda: run.scalar(run.EXECUTION_DSN,
        "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
    status, entry = run.rows(run.APPOINTMENTS_DSN,
        "SELECT status,entry_authorized_at FROM appointments WHERE appointment_id=%s",
        (uuid.UUID(appointment),))[0]
    assert status == "SCHEDULED" and entry is not None
    run.execute(run.EXECUTION_DSN, "UPDATE tank_projection SET status='ACTIVE' WHERE tank_id=%s",
                (uuid.UUID(self.ids["tank_immuno"]),))
    now2 = run.utcnow()
    self.command("wash-execution", "ResolvePendingReassignment", {
        "washExecutionId": execution, "cabinId": self.ids["cabin_immuno"], "tankId": self.ids["tank_immuno"],
        "resolvedByAccountId": supervisor, "resolvedAt": run.iso(now2),
        "expectedVersion": self.execution_version(execution),
    }, self.account_actor(supervisor), occurred_at=now2)
    run.wait_until("initial assignment after pending", lambda: run.scalar(run.EXECUTION_DSN,
        "SELECT assignment_type FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL",
        (uuid.UUID(execution),)) == "INITIAL")


run.Harness.scenario_07_pending_then_initial = pending_initial_without_capacity_loss


def exact_cleaning_release_assertion(self):
    print("[12] CleaningPeriodStarted uses actual releasedAt, not slot.endsAt")
    payload_text = run.scalar(run.EXECUTION_DSN,
        "SELECT payload_json::text FROM outbox_messages WHERE message_type='CleaningPeriodStarted' "
        "ORDER BY created_at LIMIT 1")
    assert payload_text, "a physical CleaningPeriodStarted Event is required"
    payload = run.json.loads(payload_text)
    assignment_id = uuid.UUID(payload["resourceAssignmentId"])
    starts = run.datetime.fromisoformat(payload["startsAt"].replace("Z", "+00:00"))
    released, slot_end = run.rows(run.EXECUTION_DSN,
        "SELECT a.released_at,s.ends_at FROM resource_assignments a "
        "JOIN appointment_time_slot_projection s ON s.appointment_time_slot_id=a.appointment_time_slot_id "
        "WHERE a.resource_assignment_id=%s", (assignment_id,))[0]
    assert starts == released, (starts, released)
    assert starts != slot_end, "physical cleaning must not be anchored to planned slot end"


run.Harness.scenario_12_cleaning_actual_release = exact_cleaning_release_assertion


def exact_same_version_assertion(self):
    print("[P1] same executionVersion facts remain distinct")
    appointment = uuid.UUID(self.ids["normal_appointment"])
    execution = uuid.UUID(self.ids["normal_execution"])
    entry_version = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT entry_authorized_execution_version FROM wash_execution_evidence_projection "
        "WHERE appointment_id=%s", (appointment,))
    started_version = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT execution_version FROM wash_execution_transition_projection "
        "WHERE wash_execution_id=%s AND event_type='WashExecutionStarted' ORDER BY applied_at LIMIT 1",
        (execution,))
    assert entry_version is not None and started_version is not None
    assert int(entry_version) == int(started_version), (entry_version, started_version)
    entry_event = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT entry_authorized_source_event_id FROM wash_execution_evidence_projection WHERE appointment_id=%s",
        (appointment,))
    started_event = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT source_event_id FROM wash_execution_transition_projection "
        "WHERE wash_execution_id=%s AND event_type='WashExecutionStarted' ORDER BY applied_at LIMIT 1",
        (execution,))
    assert entry_event != started_event, "dedupe must be by source event id, not aggregate version"


run.Harness.same_version_and_late_arrival_assertions = exact_same_version_assertion


for name in ("scenario_04_cancel_immuno_without_use", "scenario_05_missed_immuno_without_use"):
    original = getattr(run.Harness, name)
    def wrapper(self, _original=original):
        _original(self)
        cancel_scheduled_immuno(self)
    setattr(run.Harness, name, wrapper)

for name in ("scenario_07_pending_then_initial", "scenario_08_failure_reassignment", "scenario_11_cross_slot_no_capacity_leak"):
    original = getattr(run.Harness, name)
    def wrapper(self, supervisor, _original=original):
        _original(self, supervisor)
        finalize_in_progress(self)
        expire_open_cleanings(self)
    setattr(run.Harness, name, wrapper)

_original_09 = run.Harness.scenario_09_failure_no_alternative_cancel

def scenario_09(self, supervisor):
    _original_09(self, supervisor)
    expire_open_cleanings(self)

run.Harness.scenario_09_failure_no_alternative_cancel = scenario_09


def run_main(self):
    self.connect(); self.wait_infrastructure()
    self.seed_admin_owner()
    supervisor = run.uid(); self.ids["supervisor"] = supervisor
    self.seed_identity(supervisor, "SUPERVISOR_LAVADO")
    self.mark_execution_ready()
    self.scenario_01_normal_complete(supervisor)
    late_decision_after_valid_arrival(self, supervisor)
    self.scenario_02_journey_preferred_fallback(supervisor)
    self.scenario_03_immuno_cleaning(supervisor)
    self.scenario_04_cancel_immuno_without_use()
    self.scenario_05_missed_immuno_without_use()
    self.scenario_06_entry_rejected_immuno(supervisor)
    self.scenario_07_pending_then_initial(supervisor)
    self.scenario_08_failure_reassignment(supervisor)
    self.scenario_09_failure_no_alternative_cancel(supervisor)
    self.scenario_10_future_capacity_loss(supervisor)
    self.scenario_11_cross_slot_no_capacity_leak(supervisor)
    self.scenario_12_cleaning_actual_release()
    self.scenario_13_last_capacity_race(supervisor)
    self.scenario_14_assign_vs_disable(supervisor)
    self.scenario_15_projection_recovery()
    self.same_version_and_late_arrival_assertions()
    self.preventive_immuno_symmetry()
    self.prepare_restart_probe()
    self.close()
    print("main distributed scenarios: PASS")

run.Harness.run_main = run_main


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("main", "restart"), required=True)
    args = parser.parse_args()
    harness = run.Harness()
    if args.phase == "main":
        harness.run_main()
    else:
        harness.run_restart()


if __name__ == "__main__":
    main()
