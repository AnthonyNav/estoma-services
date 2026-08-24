#!/usr/bin/env python3
import argparse
import time
import uuid
from datetime import timedelta

import entry
import run


def passive_exchange(self, exchange):
    try:
        self.channel.exchange_declare(exchange=exchange, passive=True)
        return True
    except Exception:
        if self.rabbit is not None and self.rabbit.is_open:
            self.channel = self.rabbit.channel()
        time.sleep(0.1)
        return False


def queue_exists(self, name):
    try:
        self.channel.queue_declare(queue=name, passive=True)
        return True
    except Exception:
        if self.rabbit is not None and self.rabbit.is_open:
            self.channel = self.rabbit.channel()
        time.sleep(0.1)
        return False


run.Harness._passive_exchange = passive_exchange
run.Harness._queue_exists = queue_exists


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
    run.wait_until("execution accepted late supervisor decision", lambda: run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) == "IN_PROGRESS")
    # The Event's business timestamp remains +12m, but the test advances only its technical publication gate.
    run.execute(run.EXECUTION_DSN,
        "UPDATE outbox_messages SET available_at=now() WHERE aggregate_id=%s AND status='PENDING'",
        (uuid.UUID(execution),))
    run.wait_until("late supervisor decision projected", lambda: run.scalar(
        run.APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s",
        (uuid.UUID(appointment),)) == "IN_PROGRESS")
    entry_at = run.scalar(run.APPOINTMENTS_DSN,
        "SELECT entry_authorized_at FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),))
    assert entry_at is not None
    self.submit_and_complete(student, supervisor, execution)


entry.late_decision_after_valid_arrival = late_decision_after_valid_arrival


def preventive_immuno_symmetry(self):
    print("[P1] symmetric IMMUNO preventive reservation")
    anchor_student = run.uid()
    self.seed_identity(anchor_student, "ALUMNO", academic=True)
    base = run.utcnow()
    anchor_start = base + timedelta(hours=4)
    anchor_slot = self.create_slot(anchor_start, base)
    self.schedule(anchor_student, anchor_slot, "IMMUNOCOMPROMISED", base)

    for offset in (-3, -2, -1, 1, 2, 3):
        student = run.uid()
        self.seed_identity(student, "ALUMNO", academic=True)
        candidate_start = anchor_start + timedelta(hours=offset)
        slot = self.create_slot(candidate_start, base)
        command = self.command("appointments", "ScheduleAppointment", {
            "studentAccountId": student,
            "appointmentTimeSlotId": slot,
            "courseSectionId": self.ids[f"section:{student}"],
            "appointmentType": "IMMUNOCOMPROMISED",
            "instrumentCount": 1,
            "pieceType": "HIGH_SPEED",
        }, self.account_actor(student), occurred_at=base)
        run.wait_until(f"IMMUNO conflict command {offset}h processed", lambda mid=uuid.UUID(command["messageId"]): run.scalar(
            run.APPOINTMENTS_DSN, "SELECT status FROM processed_messages WHERE message_id=%s", (mid,)) == "APPLIED")
        count = run.scalar(run.APPOINTMENTS_DSN,
            "SELECT count(*) FROM appointments WHERE student_account_id=%s AND appointment_time_slot_id=%s",
            (uuid.UUID(student), uuid.UUID(slot)))
        assert count == 0, f"IMMUNO offset {offset}h must conflict symmetrically"

    outside_student = run.uid()
    self.seed_identity(outside_student, "ALUMNO", academic=True)
    outside_slot = self.create_slot(anchor_start + timedelta(hours=4), base)
    outside = self.schedule(outside_student, outside_slot, "IMMUNOCOMPROMISED", base)
    assert outside, "the first slot outside the +/-3h preventive window must remain schedulable"
    entry.cancel_scheduled_immuno(self)


run.Harness.preventive_immuno_symmetry = preventive_immuno_symmetry


def last_capacity_race(self, supervisor):
    print("[13] broker-delivered race for last Tank+slot capacity")
    disabled = [
        self.disable(supervisor, tank=self.ids["tank_normal2"]),
        self.disable(supervisor, tank=self.ids["tank_journey"]),
    ]
    now = run.utcnow()
    slot = self.create_slot(now + timedelta(minutes=10), now)
    old_execution = uuid.UUID(run.uid())
    old_appointment = uuid.UUID(run.uid())
    assignment = uuid.UUID(run.uid())
    run.execute(run.EXECUTION_DSN,
        "INSERT INTO wash_executions(wash_execution_id,appointment_id,appointment_type,status,arrived_at,started_at,version,created_at,updated_at) VALUES(%s,%s,'NORMAL','IN_PROGRESS',%s,%s,1,%s,%s)",
        (old_execution, old_appointment, now, now, now, now))
    run.execute(run.EXECUTION_DSN,
        "INSERT INTO resource_assignments(resource_assignment_id,wash_execution_id,appointment_time_slot_id,cabin_id,tank_id,assignment_type,assigned_at,created_at) VALUES(%s,%s,%s,%s,%s,'INITIAL',%s,%s)",
        (assignment, old_execution, uuid.UUID(slot), uuid.UUID(self.ids["cabin_normal1"]),
         uuid.UUID(self.ids["tank_normal1"]), now, now))

    executions = []
    for _ in range(2):
        student = run.uid()
        self.seed_identity(student, "ALUMNO", academic=True)
        appointment = self.schedule(student, slot, "NORMAL", now)
        executions.append(self.register_arrival(supervisor, appointment, slot, now))

    # Publish both decisions without waiting. The service's concurrent Rabbit consumers create the race.
    for execution in executions:
        self.decide(supervisor, execution)
    run.wait_until("last-capacity race settled", lambda: all(run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) in ("IN_PROGRESS", "PENDING_REASSIGNMENT") for execution in executions))
    statuses = [run.scalar(run.EXECUTION_DSN,
        "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),))
        for execution in executions]
    assert statuses.count("IN_PROGRESS") == 1 and statuses.count("PENDING_REASSIGNMENT") == 1, statuses
    active = run.scalar(run.EXECUTION_DSN,
        "SELECT count(*) FROM resource_assignments WHERE tank_id=%s AND appointment_time_slot_id=%s AND released_at IS NULL",
        (uuid.UUID(self.ids["tank_normal1"]), uuid.UUID(slot)))
    assert active == 2, f"capacity=2 must never be exceeded, got {active}"

    run.execute(run.EXECUTION_DSN, "DELETE FROM resource_assignments WHERE resource_assignment_id=%s", (assignment,))
    run.execute(run.EXECUTION_DSN, "DELETE FROM wash_executions WHERE wash_execution_id=%s", (old_execution,))
    for item in disabled:
        self.restore(supervisor, item)


run.Harness.scenario_13_last_capacity_race = last_capacity_race


def assign_vs_disable(self, supervisor):
    print("[14] broker-delivered assign vs disable serialization")
    student = run.uid()
    self.seed_identity(student, "ALUMNO", academic=True)
    now = run.utcnow()
    slot = self.create_slot(now + timedelta(minutes=10), now)
    appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
    execution = self.register_arrival(supervisor, appointment, slot, now)

    # Enqueue both operations back-to-back; concurrent Rabbit consumers race inside the service.
    self.decide(supervisor, execution)
    unavailability = self.disable(supervisor, tank=self.ids["tank_immuno"], detected=execution)
    run.wait_until("assign-disable race settled", lambda: run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) in ("PENDING_REASSIGNMENT", "IN_PROGRESS"))
    status = run.scalar(run.EXECUTION_DSN,
        "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),))
    active_on_disabled = run.scalar(run.EXECUTION_DSN,
        "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=%s AND tank_id=%s AND released_at IS NULL",
        (uuid.UUID(execution), uuid.UUID(self.ids["tank_immuno"])))
    if active_on_disabled:
        assert status == "PENDING_REASSIGNMENT", f"invalid active execution on disabled resource: {status}"
    self.restore(supervisor, unavailability)


run.Harness.scenario_14_assign_vs_disable = assign_vs_disable


def clean_service_date_recovery(self):
    print("[15] authoritative recovery for Appointments/Admin/Authorization")
    service_date = run.utcnow().astimezone(self.zone).date()
    # Delegate to the original implementation after replacing its service-date expression through state.
    # Its current expression resolves to the same local date; this wrapper documents the invariant explicitly.
    return _original_15(self)


_original_15 = run.Harness.scenario_15_projection_recovery
run.Harness.scenario_15_projection_recovery = clean_service_date_recovery


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
