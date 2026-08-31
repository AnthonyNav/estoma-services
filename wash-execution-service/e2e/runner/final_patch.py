#!/usr/bin/env python3
import uuid

import hardening
import run


def safe_zone():
    now = run.utcnow()
    for name in (
        "America/Mexico_City", "Pacific/Honolulu", "America/Los_Angeles",
        "UTC", "Europe/London", "Asia/Tokyo", "Pacific/Auckland",
    ):
        zone = hardening.entry.ZoneInfo(name)
        if 8 <= now.astimezone(zone).hour <= 13:
            return zone
    return hardening.entry.ZoneInfo("UTC")


# Scenario 7/13/P1 create slots up to +8h; keep their one-hour duration before the 23:00 fixture close.
run.choose_zone = safe_zone

_original_admin_outbox = run.Harness._admin_outbox


def admin_outbox_with_owner_state(self, cur, message_type, aggregate_id, version, payload, now):
    # An owner Event must never advertise state the authoritative owner DB does not contain.
    if message_type == "TankUpdated":
        cur.execute(
            "UPDATE tanks SET cabin_id=%s,code=%s,name=%s,configured_capacity=%s,status=%s,version=%s,updated_at=%s "
            "WHERE tank_id=%s",
            (uuid.UUID(payload["cabinId"]), payload["code"], payload.get("name"),
             int(payload["configuredCapacity"]), payload["status"], int(version), now, aggregate_id),
        )
    return _original_admin_outbox(self, cur, message_type, aggregate_id, version, payload, now)


run.Harness._admin_outbox = admin_outbox_with_owner_state


def assign_vs_disable(self, supervisor):
    print("[14] ResolvePendingReassignment vs DisableOperationalResource")
    student = run.uid()
    self.seed_identity(student, "ALUMNO", academic=True)
    now = run.utcnow()
    slot = self.create_slot(now + run.timedelta(minutes=10), now)
    appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)

    # Establish authorized PENDING state without broadcasting a capacity-loss Event to Appointments.
    run.execute(run.EXECUTION_DSN, "UPDATE tank_projection SET status='INACTIVE' WHERE tank_id=%s",
                (uuid.UUID(self.ids["tank_immuno"]),))
    execution = self.register_arrival(supervisor, appointment, slot, now)
    self.decide(supervisor, execution)
    run.wait_until("authorized pending before race", lambda: run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
    run.wait_until("entryAuthorizedAt while appointment remains scheduled", lambda: run.rows(
        run.APPOINTMENTS_DSN,
        "SELECT status,entry_authorized_at FROM appointments WHERE appointment_id=%s",
        (uuid.UUID(appointment),))[0][0] == "SCHEDULED" and run.rows(
        run.APPOINTMENTS_DSN,
        "SELECT status,entry_authorized_at FROM appointments WHERE appointment_id=%s",
        (uuid.UUID(appointment),))[0][1] is not None)
    run.execute(run.EXECUTION_DSN, "UPDATE tank_projection SET status='ACTIVE' WHERE tank_id=%s",
                (uuid.UUID(self.ids["tank_immuno"]),))

    resolved_at = run.utcnow()
    self.command("wash-execution", "ResolvePendingReassignment", {
        "washExecutionId": execution,
        "cabinId": self.ids["cabin_immuno"],
        "tankId": self.ids["tank_immuno"],
        "resolvedByAccountId": supervisor,
        "resolvedAt": run.iso(resolved_at),
        "expectedVersion": self.execution_version(execution),
    }, self.account_actor(supervisor), occurred_at=resolved_at)
    unavailability = self.disable(
        supervisor, tank=self.ids["tank_immuno"], detected=execution)

    run.wait_until("assign-disable serialized", lambda: run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
    active = run.scalar(run.EXECUTION_DSN,
        "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=%s AND tank_id=%s AND released_at IS NULL",
        (uuid.UUID(execution), uuid.UUID(self.ids["tank_immuno"])))
    if active:
        assert run.scalar(run.EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT"

    self.restore(supervisor, unavailability)
    cancelled_at = run.utcnow()
    self.command("wash-execution", "CancelWashExecutionByClinic", {
        "washExecutionId": execution,
        "cancellationSubreason": "CAPACITY_LOSS",
        "cancellationReason": "PR4_E2E_CLEANUP",
        "cancelledByAccountId": supervisor,
        "cancelledAt": run.iso(cancelled_at),
        "expectedVersion": self.execution_version(execution),
    }, self.account_actor(supervisor), occurred_at=cancelled_at)
    run.wait_until("assign-disable cleanup", lambda: run.scalar(
        run.EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s",
        (uuid.UUID(execution),)) == "CANCELLED")
    hardening.entry.expire_open_cleanings(self)


run.Harness.scenario_14_assign_vs_disable = assign_vs_disable


if __name__ == "__main__":
    hardening.main()
