#!/usr/bin/env python3
import argparse
import json
import os
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pika
import psycopg

RABBIT_HOST = os.getenv("RABBIT_HOST", "rabbitmq")
RABBIT_USER = os.getenv("RABBIT_USER", "estoma")
RABBIT_PASSWORD = os.getenv("RABBIT_PASSWORD", "estoma")
APPOINTMENTS_DSN = os.environ["APPOINTMENTS_DSN"]
ADMIN_DSN = os.environ["WASH_ADMIN_DSN"]
EXECUTION_DSN = os.environ["WASH_EXECUTION_DSN"]
AUTH_DSN = os.environ["AUTHORIZATION_DSN"]

COMMANDS = "platform.commands"
EVENTS = "platform.events"
QUERIES = "platform.queries"
RESULTS = "platform.results"


def uid():
    return str(uuid.uuid4())


def utcnow():
    return datetime.now(timezone.utc)


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def wait_until(description, fn, timeout=30.0, interval=0.1):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception as exc:  # services/databases may still be starting
            last = exc
        time.sleep(interval)
    raise AssertionError(f"timeout waiting for {description}; last={last!r}")


def db(dsn):
    return psycopg.connect(dsn, autocommit=True)


def scalar(dsn, sql, params=()):
    with db(dsn) as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        row = cur.fetchone()
        return None if row is None else row[0]


def rows(dsn, sql, params=()):
    with db(dsn) as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def execute(dsn, sql, params=()):
    with db(dsn) as conn, conn.cursor() as cur:
        cur.execute(sql, params)


def wait_table(dsn, table):
    wait_until(table, lambda: scalar(dsn, "SELECT to_regclass(%s)", (table,)) == table, timeout=90)


def choose_zone():
    now = utcnow()
    for name in ("America/Mexico_City", "Pacific/Honolulu", "Asia/Tokyo", "UTC"):
        zone = ZoneInfo(name)
        hour = now.astimezone(zone).hour
        if 8 <= hour <= 19:
            return zone
    return ZoneInfo("UTC")


class Harness:
    def __init__(self):
        self.zone = choose_zone()
        self.rabbit = None
        self.channel = None
        self.result_queue = "pr4.e2e.results.bff"
        self.execution_result_queue = "pr4.e2e.results.wash-execution"
        self.ids = {}
        self.unavailability = {}

    def connect(self):
        credentials = pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD)
        params = pika.ConnectionParameters(
            host=RABBIT_HOST,
            credentials=credentials,
            heartbeat=30,
            blocked_connection_timeout=30,
            connection_attempts=30,
            retry_delay=1,
        )
        self.rabbit = pika.BlockingConnection(params)
        self.channel = self.rabbit.channel()
        for exchange in (COMMANDS, EVENTS, QUERIES, RESULTS):
            wait_until(exchange, lambda e=exchange: self._passive_exchange(e), timeout=60)
        self.channel.queue_declare(queue=self.result_queue, durable=False, auto_delete=True)
        self.channel.queue_bind(queue=self.result_queue, exchange=RESULTS, routing_key="bff")
        self.channel.queue_declare(queue=self.execution_result_queue, durable=False, auto_delete=True)
        self.channel.queue_bind(queue=self.execution_result_queue, exchange=RESULTS, routing_key="wash-execution")

    def _passive_exchange(self, exchange):
        try:
            self.channel.exchange_declare(exchange=exchange, passive=True)
            return True
        except Exception:
            if self.rabbit and self.rabbit.is_closed:
                self.connect()
            return False

    def close(self):
        if self.rabbit and self.rabbit.is_open:
            self.rabbit.close()

    def publish(self, exchange, routing_key, envelope):
        self.channel.basic_publish(
            exchange=exchange,
            routing_key=routing_key,
            body=json.dumps(envelope, separators=(",", ":")).encode(),
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                message_id=envelope["messageId"],
            ),
            mandatory=True,
        )

    def envelope(self, message_type, kind, producer, payload, actor, aggregate_id=None,
                 aggregate_version=None, occurred_at=None, correlation_id=None,
                 causation_id=None, operation_id=None, idempotency_key=None, message_id=None):
        return {
            "messageId": message_id or uid(),
            "messageType": message_type,
            "messageVersion": 1,
            "messageKind": kind,
            "producer": producer,
            "occurredAt": iso(occurred_at or utcnow()),
            "correlationId": correlation_id or uid(),
            "causationId": causation_id,
            "operationId": operation_id,
            "idempotencyKey": idempotency_key,
            "aggregateId": aggregate_id,
            "aggregateVersion": aggregate_version,
            "actor": actor,
            "payload": payload,
        }

    @staticmethod
    def account_actor(account_id):
        return {"type": "ACCOUNT", "accountId": account_id, "service": None}

    @staticmethod
    def system_actor(service):
        return {"type": "SYSTEM", "accountId": None, "service": service}

    def command(self, owner, message_type, payload, actor, occurred_at=None, producer="bff"):
        message_id = uid()
        correlation_id = uid()
        operation_id = uid()
        envelope = self.envelope(
            message_type, "COMMAND", producer, payload, actor,
            occurred_at=occurred_at, correlation_id=correlation_id,
            operation_id=operation_id, idempotency_key=f"e2e:{message_type}:{message_id}",
            message_id=message_id,
        )
        self.publish(COMMANDS, owner, envelope)
        return envelope

    def event(self, producer, routing_key, message_type, payload, aggregate_id,
              aggregate_version=1, occurred_at=None, message_id=None):
        envelope = self.envelope(
            message_type, "EVENT", producer, payload, self.system_actor(producer),
            aggregate_id=aggregate_id, aggregate_version=aggregate_version,
            occurred_at=occurred_at, message_id=message_id,
        )
        self.publish(EVENTS, routing_key, envelope)
        return envelope

    def query(self, owner, producer, message_type, payload, query_id, operation_id, correlation_id):
        envelope = self.envelope(
            message_type, "QUERY", producer, payload, self.system_actor(producer),
            occurred_at=utcnow(), correlation_id=correlation_id,
            operation_id=operation_id, message_id=query_id,
        )
        self.publish(QUERIES, owner, envelope)
        return envelope

    def wait_result(self, queue, correlation_id, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            method, props, body = self.channel.basic_get(queue=queue, auto_ack=True)
            if method:
                value = json.loads(body)
                if value.get("correlationId") == correlation_id:
                    return value
            else:
                time.sleep(0.05)
        raise AssertionError(f"result not received for correlation {correlation_id}")

    # ---------- bootstrap fixtures through owner contracts ----------

    def wait_infrastructure(self):
        wait_table(APPOINTMENTS_DSN, "appointments")
        wait_table(ADMIN_DSN, "service_calendars")
        wait_table(EXECUTION_DSN, "wash_executions")
        wait_table(AUTH_DSN, "systems")
        wait_until("appointments command queue", lambda: self._queue_exists("commands.appointments"), timeout=60)
        wait_until("execution command queue", lambda: self._queue_exists("commands.wash-execution"), timeout=60)
        wait_until("wash admin query queue", lambda: self._queue_exists("queries.wash-administration"), timeout=60)
        wait_until("authorization query queue", lambda: self._queue_exists("queries.authorization"), timeout=60)

    def _queue_exists(self, name):
        try:
            self.channel.queue_declare(queue=name, passive=True)
            return True
        except Exception:
            return False

    def seed_admin_owner(self):
        now = utcnow()
        local = now.astimezone(self.zone)
        service_date = local.date()
        actor = uuid.UUID(uid())
        calendar = uuid.UUID(uid())
        schedule = uuid.UUID(uid())
        cabins = {
            "normal1": uuid.UUID(uid()),
            "normal2": uuid.UUID(uid()),
            "journey": uuid.UUID(uid()),
            "immuno": uuid.UUID(uid()),
        }
        tanks = {name: uuid.UUID(uid()) for name in cabins}
        self.ids.update({
            "calendar": str(calendar), "schedule": str(schedule),
            **{f"cabin_{k}": str(v) for k, v in cabins.items()},
            **{f"tank_{k}": str(v) for k, v in tanks.items()},
        })
        with db(ADMIN_DSN) as conn, conn.cursor() as cur:
            cur.execute(
                "INSERT INTO service_calendars(service_calendar_id,code,name,timezone,effective_from,effective_until,status,version,created_by_account_id,updated_by_account_id,created_at,updated_at) VALUES(%s,%s,%s,%s,%s,%s,'ACTIVE',1,%s,%s,%s,%s)",
                (calendar, "E2E-CALENDAR", "PR4 E2E", self.zone.key, service_date - timedelta(days=1),
                 service_date + timedelta(days=1), actor, actor, now, now),
            )
            cur.execute(
                "INSERT INTO service_schedules(service_schedule_id,service_calendar_id,day_of_week,opening_time,closing_time,slot_duration_minutes,status,version,created_by_account_id,updated_by_account_id,created_at,updated_at) VALUES(%s,%s,%s,'00:00','23:00',60,'ACTIVE',1,%s,%s,%s,%s)",
                (schedule, calendar, service_date.isoweekday(), actor, actor, now, now),
            )
            specs = [
                ("normal1", "NORMAL", 2, "N-1"),
                ("normal2", "NORMAL", 2, "N-2"),
                ("journey", "JOURNEY", 2, "J-1"),
                ("immuno", "IMMUNOCOMPROMISED", 1, "I-1"),
            ]
            for name, cabin_type, capacity, code in specs:
                cur.execute(
                    "INSERT INTO cabins(cabin_id,code,name,cabin_type,status,version,created_by_account_id,updated_by_account_id,created_at,updated_at) VALUES(%s,%s,%s,%s,'ACTIVE',1,%s,%s,%s,%s)",
                    (cabins[name], f"C-{code}", f"Cabin {code}", cabin_type, actor, actor, now, now),
                )
                cur.execute(
                    "INSERT INTO tanks(tank_id,cabin_id,code,name,configured_capacity,status,version,created_by_account_id,updated_by_account_id,created_at,updated_at) VALUES(%s,%s,%s,%s,%s,'ACTIVE',1,%s,%s,%s,%s)",
                    (tanks[name], cabins[name], f"T-{code}", f"Tank {code}", capacity, actor, actor, now, now),
                )
            priorities = [
                ("NORMAL", "NORMAL", "PREFERRED", 1),
                ("NORMAL", "JOURNEY", "ALTERNATIVE", 2),
                ("JOURNEY", "JOURNEY", "PREFERRED", 1),
                ("JOURNEY", "NORMAL", "ALTERNATIVE", 2),
                ("IMMUNOCOMPROMISED", "IMMUNOCOMPROMISED", "EXCLUSIVE", 1),
            ]
            for appt_type, cabin_type, mode, order in priorities:
                priority_id = uuid.UUID(uid())
                cur.execute(
                    "INSERT INTO resource_priorities(resource_priority_id,appointment_type,cabin_type,usage_mode,priority_order,status,version,created_at,updated_at) VALUES(%s,%s,%s,%s,%s,'ACTIVE',1,%s,%s)",
                    (priority_id, appt_type, cabin_type, mode, order, now, now),
                )
                self._admin_outbox(cur, "ResourcePriorityConfigured", priority_id, 1, {
                    "resourcePriorityId": str(priority_id), "appointmentType": appt_type,
                    "cabinType": cabin_type, "usageMode": mode, "priorityOrder": order,
                    "status": "ACTIVE", "version": 1, "occurredAt": iso(now),
                }, now)
            self._admin_outbox(cur, "ServiceCalendarCreated", calendar, 1, {
                "calendarId": str(calendar), "code": "E2E-CALENDAR", "name": "PR4 E2E",
                "timezone": self.zone.key, "effectiveFrom": str(service_date - timedelta(days=1)),
                "effectiveUntil": str(service_date + timedelta(days=1)), "status": "ACTIVE",
                "version": 1, "occurredAt": iso(now),
            }, now)
            self._admin_outbox(cur, "ServiceScheduleConfigured", schedule, 1, {
                "scheduleId": str(schedule), "calendarId": str(calendar),
                "dayOfWeek": service_date.isoweekday(), "openingTime": "00:00:00",
                "closingTime": "23:00:00", "slotDurationMinutes": 60, "status": "ACTIVE",
                "version": 1, "occurredAt": iso(now),
            }, now)
            for name, cabin_type, capacity, code in specs:
                self._admin_outbox(cur, "CabinRegistered", cabins[name], 1, {
                    "cabinId": str(cabins[name]), "code": f"C-{code}", "name": f"Cabin {code}",
                    "cabinType": cabin_type, "status": "ACTIVE", "version": 1, "occurredAt": iso(now),
                }, now)
                self._admin_outbox(cur, "TankRegistered", tanks[name], 1, {
                    "tankId": str(tanks[name]), "cabinId": str(cabins[name]), "code": f"T-{code}",
                    "name": f"Tank {code}", "configuredCapacity": capacity, "status": "ACTIVE",
                    "version": 1, "occurredAt": iso(now),
                }, now)
        wait_until("wash admin outbox published", lambda: scalar(
            ADMIN_DSN, "SELECT count(*)=0 FROM outbox_messages WHERE status='PENDING'") is True, timeout=30)
        wait_until("appointments admin projection", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM tank_projection") >= 4)
        wait_until("execution admin projection", lambda: scalar(
            EXECUTION_DSN, "SELECT count(*) FROM tank_projection") >= 4)
        return service_date

    def _admin_outbox(self, cur, message_type, aggregate_id, version, payload, now):
        cur.execute(
            """INSERT INTO outbox_messages(message_id,message_type,message_version,message_kind,producer,routing_key,
            occurred_at,operation_id,idempotency_key,aggregate_id,aggregate_version,correlation_id,causation_id,
            actor,payload,metadata,status,attempts,available_at) VALUES(%s,%s,1,'EVENT','wash-administration','events',
            %s,NULL,NULL,%s,%s,%s,NULL,%s::jsonb,%s::jsonb,NULL,'PENDING',0,%s)""",
            (uuid.UUID(uid()), message_type, now, aggregate_id, version, uuid.UUID(uid()),
             json.dumps(self.system_actor("wash-administration")), json.dumps(payload), now),
        )

    def seed_identity(self, account_id, role_code, academic=False):
        now = utcnow()
        account = uuid.UUID(account_id)
        system_id = uuid.UUID(self.ids.setdefault("auth_system", uid()))
        role_id = uuid.uuid5(uuid.NAMESPACE_URL, f"estoma-e2e:{role_code}")
        access_id = uuid.uuid5(uuid.NAMESPACE_URL, f"estoma-e2e:access:{account_id}")
        with db(AUTH_DSN) as conn, conn.cursor() as cur:
            cur.execute("INSERT INTO systems(system_id,code,name,status,created_at,updated_at) VALUES(%s,'LAVADO_ULTRASONICO','Lavado','ACTIVE',%s,%s) ON CONFLICT(code) DO NOTHING", (system_id, now, now))
            cur.execute("SELECT system_id FROM systems WHERE code='LAVADO_ULTRASONICO'")
            system_id = cur.fetchone()[0]
            cur.execute("INSERT INTO roles(role_id,code,name,status,created_at,updated_at) VALUES(%s,%s,%s,'ACTIVE',%s,%s) ON CONFLICT(code) DO NOTHING", (role_id, role_code, role_code, now, now))
            cur.execute("SELECT role_id FROM roles WHERE code=%s", (role_code,))
            role_id = cur.fetchone()[0]
            cur.execute("INSERT INTO role_systems(role_id,system_id,assigned_at) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING", (role_id, system_id, now))
            cur.execute("INSERT INTO account_roles(account_id,role_id,version,assigned_by_actor_type,assigned_by_actor_service,assigned_at,updated_at) VALUES(%s,%s,1,'SYSTEM','pr4-e2e',%s,%s) ON CONFLICT(account_id) DO NOTHING", (account, role_id, now, now))
            cur.execute("INSERT INTO system_accesses(system_access_id,account_id,system_id,status,version,granted_by_actor_type,granted_by_actor_service,granted_at,updated_at) VALUES(%s,%s,%s,'ACTIVE',1,'SYSTEM','pr4-e2e',%s,%s) ON CONFLICT(account_id,system_id) DO NOTHING", (access_id, account, system_id, now, now))
        self.event("authorization", "authorization.account-role.assigned", "AccountRoleAssigned", {
            "accountId": account_id, "roleId": str(role_id), "roleCode": role_code,
        }, account_id, 1, now)
        self.event("authorization", "authorization.system-access.granted", "SystemAccessGranted", {
            "systemAccessId": str(access_id), "accountId": account_id, "systemId": str(system_id),
            "systemCode": "LAVADO_ULTRASONICO", "status": "ACTIVE",
        }, str(access_id), 1, now)
        wait_until(f"execution authorization {account_id}", lambda: scalar(
            EXECUTION_DSN, "SELECT count(*) FROM authorization_role_projection WHERE account_id=%s", (account,)) == 1)
        wait_until(f"appointments authorization {account_id}", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM account_role_projection WHERE account_id=%s", (account,)) == 1)
        if academic:
            self.seed_academic(account_id)

    def seed_academic(self, student_id):
        now = utcnow()
        period = uuid.UUID(uid())
        profile = uuid.UUID(uid())
        section = uuid.UUID(uid())
        self.ids[f"section:{student_id}"] = str(section)
        self.event("academic", "academic.academic-period.activated", "AcademicPeriodActivated", {
            "academicPeriodId": str(period), "code": "E2E-2026", "status": "ACTIVE",
        }, str(period), 1, now)
        self.event("academic", "academic.student-profile.created", "StudentAcademicProfileCreated", {
            "studentAcademicProfileId": str(profile), "accountId": student_id,
            "currentSemester": 5, "academicStatus": "ACTIVE",
        }, str(profile), 1, now)
        self.event("academic", "academic.course-section.registered", "CourseSectionRegistered", {
            "courseSectionId": str(section), "academicPeriodId": str(period), "status": "ACTIVE",
        }, str(section), 1, now)
        self.event("academic", "academic.student-course-section.enrolled", "StudentEnrolledInCourseSection", {
            "studentAcademicProfileId": str(profile), "accountId": student_id,
            "courseSectionId": str(section), "academicPeriodId": str(period), "enrolledAt": iso(now),
        }, str(section), 1, now)
        wait_until(f"academic enrollment {student_id}", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM student_course_section_projection WHERE student_account_id=%s", (uuid.UUID(student_id),)) == 1)

    def mark_execution_ready(self):
        now = utcnow()
        with db(EXECUTION_DSN) as conn, conn.cursor() as cur:
            for name in ("appointments", "wash-administration", "authorization"):
                cur.execute("""INSERT INTO projection_recovery_state(projection_name,source_logical_component_id,readiness_status,last_successful_rebuild_at,last_error,updated_at)
                    VALUES(%s,%s,'READY',%s,NULL,%s) ON CONFLICT(projection_name) DO UPDATE SET readiness_status='READY',last_error=NULL,updated_at=excluded.updated_at""",
                    (name, name, now, now))
        execute(APPOINTMENTS_DSN, """INSERT INTO projection_recovery_state(projection_name,status,source_version,updated_at,last_error,version)
            VALUES('wash-execution','READY',0,%s,NULL,1) ON CONFLICT(projection_name) DO UPDATE SET status='READY',last_error=NULL,updated_at=excluded.updated_at""", (now,))

    # ---------- owner commands ----------

    def create_slot(self, start, command_now=None):
        command_now = command_now or utcnow()
        slot = uid()
        payload = {
            "slotId": slot,
            "sourceScheduleId": self.ids["schedule"],
            "serviceDate": str(start.astimezone(self.zone).date()),
            "startsAt": iso(start), "endsAt": iso(start + timedelta(hours=1)),
            "timezone": self.zone.key,
            "bookingDeadlineAt": iso(start - timedelta(minutes=5)),
            "cancellationDeadlineAt": iso(start - timedelta(minutes=20)),
            "arrivalToleranceUntil": iso(start + timedelta(minutes=10)),
        }
        self.command("appointments", "CreateAppointmentTimeSlot", payload,
                     self.system_actor("appointments"), occurred_at=command_now, producer="appointments")
        wait_until("slot creation", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM appointment_time_slots WHERE appointment_time_slot_id=%s", (uuid.UUID(slot),)) == 1)
        wait_until("slot projection in execution", lambda: scalar(
            EXECUTION_DSN, "SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id=%s", (uuid.UUID(slot),)) == 1)
        wait_until("capacity projection", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM appointment_capacity_projections WHERE appointment_time_slot_id=%s", (uuid.UUID(slot),)) == 1)
        return slot

    def schedule(self, student, slot, appointment_type="NORMAL", command_now=None):
        command_now = command_now or utcnow()
        section = self.ids[f"section:{student}"]
        before = set(r[0] for r in rows(APPOINTMENTS_DSN, "SELECT appointment_id FROM appointments WHERE student_account_id=%s AND appointment_time_slot_id=%s", (uuid.UUID(student), uuid.UUID(slot))))
        self.command("appointments", "ScheduleAppointment", {
            "studentAccountId": student, "appointmentTimeSlotId": slot, "courseSectionId": section,
            "appointmentType": appointment_type, "instrumentCount": 1, "pieceType": "HIGH_SPEED",
        }, self.account_actor(student), occurred_at=command_now)
        def created():
            found = rows(APPOINTMENTS_DSN, "SELECT appointment_id,status FROM appointments WHERE student_account_id=%s AND appointment_time_slot_id=%s ORDER BY scheduled_at DESC", (uuid.UUID(student), uuid.UUID(slot)))
            for appt_id, status in found:
                if appt_id not in before and status == "SCHEDULED":
                    return str(appt_id)
            return None
        appointment = wait_until("appointment scheduled", created)
        wait_until("appointment projection in execution", lambda: scalar(
            EXECUTION_DSN, "SELECT count(*) FROM appointment_projection WHERE appointment_id=%s", (uuid.UUID(appointment),)) == 1)
        return appointment

    def register_arrival(self, supervisor, appointment, slot, arrived_at=None):
        arrived_at = arrived_at or utcnow()
        execution = uid()
        self.command("wash-execution", "RegisterWashArrival", {
            "washExecutionId": execution, "appointmentId": appointment, "arrivedAt": iso(arrived_at),
        }, self.account_actor(supervisor), occurred_at=arrived_at)
        wait_until("wash arrival", lambda: scalar(
            EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_ENTRY")
        wait_until("arrival evidence in appointments", lambda: scalar(
            APPOINTMENTS_DSN, "SELECT count(*) FROM wash_execution_evidence_projection WHERE appointment_id=%s AND arrived_at IS NOT NULL", (uuid.UUID(appointment),)) == 1)
        return execution

    def execution_version(self, execution):
        return int(scalar(EXECUTION_DSN, "SELECT version FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)))

    def decide(self, supervisor, execution, decision="AUTHORIZED", expected=None, decided_at=None):
        expected = expected or self.execution_version(execution)
        decided_at = decided_at or utcnow()
        self.command("wash-execution", "DecideWashEntry", {
            "washExecutionId": execution, "identityConfirmed": True, "requirementsSatisfied": True,
            "decision": decision, "rejectionReason": None if decision == "AUTHORIZED" else "E2E_REJECTED",
            "decidedByAccountId": supervisor, "decidedAt": iso(decided_at), "expectedVersion": expected,
        }, self.account_actor(supervisor), occurred_at=decided_at)

    def disable(self, supervisor, cabin=None, tank=None, detected=None):
        unavailability = uid()
        now = utcnow()
        self.command("wash-execution", "DisableOperationalResource", {
            "resourceUnavailabilityId": unavailability, "cabinId": cabin, "tankId": tank,
            "causeType": "FAILURE", "reason": "PR4_E2E", "disabledByAccountId": supervisor,
            "detectedDuringWashExecutionId": detected, "disabledAt": iso(now),
        }, self.account_actor(supervisor), occurred_at=now)
        wait_until("resource disabled", lambda: scalar(
            EXECUTION_DSN, "SELECT count(*) FROM resource_unavailabilities WHERE resource_unavailability_id=%s AND restored_at IS NULL", (uuid.UUID(unavailability),)) == 1)
        return unavailability

    def restore(self, supervisor, unavailability):
        version = int(scalar(EXECUTION_DSN, "SELECT version FROM resource_unavailabilities WHERE resource_unavailability_id=%s", (uuid.UUID(unavailability),)))
        now = utcnow()
        self.command("wash-execution", "RestoreOperationalResource", {
            "resourceUnavailabilityId": unavailability, "resolution": "PR4_E2E_RESTORED",
            "restoredByAccountId": supervisor, "restoredAt": iso(now), "expectedVersion": version,
        }, self.account_actor(supervisor), occurred_at=now)
        wait_until("resource restored", lambda: scalar(
            EXECUTION_DSN, "SELECT restored_at IS NOT NULL FROM resource_unavailabilities WHERE resource_unavailability_id=%s", (uuid.UUID(unavailability),)) is True)

    def submit_and_complete(self, student, supervisor, execution):
        now = utcnow()
        self.command("wash-execution", "SubmitWashExit", {
            "washExecutionId": execution, "submittedByStudentAccountId": student,
            "materials": {"packageCount": 1, "greenPaperCassette8Count": 0,
                          "greenPaperCassette10Count": 0, "witnessTapePortionCount": 0},
            "submittedAt": iso(now), "expectedVersion": self.execution_version(execution),
        }, self.account_actor(student), occurred_at=now)
        wait_until("exit submitted", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "EXIT_SUBMITTED")
        now2 = utcnow()
        self.command("wash-execution", "CompleteWashExecution", {
            "washExecutionId": execution, "completedByAccountId": supervisor,
            "finalMaterials": {"packageCount": 1, "greenPaperCassette8Count": 0,
                               "greenPaperCassette10Count": 0, "witnessTapePortionCount": 0},
            "completedAt": iso(now2), "expectedVersion": self.execution_version(execution),
        }, self.account_actor(supervisor), occurred_at=now2)
        wait_until("execution completed", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "COMPLETED")

    # ---------- scenarios ----------

    def scenario_01_normal_complete(self, supervisor):
        print("[01] NORMAL full flow")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); start = now + timedelta(minutes=10)
        slot = self.create_slot(start, now)
        appointment = self.schedule(student, slot, "NORMAL", now)
        execution = self.register_arrival(supervisor, appointment, slot, now)
        self.decide(supervisor, execution)
        wait_until("normal in progress", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "IN_PROGRESS")
        self.submit_and_complete(student, supervisor, execution)
        wait_until("appointment completed", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "COMPLETED")
        self.ids.update({"normal_student": student, "normal_slot": slot, "normal_appointment": appointment,
                         "normal_execution": execution})

    def scenario_02_journey_preferred_fallback(self, supervisor):
        print("[02] JOURNEY preferred/fallback")
        now = utcnow()
        student1 = uid(); self.seed_identity(student1, "ALUMNO", academic=True)
        slot1 = self.create_slot(now + timedelta(minutes=11), now)
        appointment1 = self.schedule(student1, slot1, "JOURNEY", now)
        execution1 = self.register_arrival(supervisor, appointment1, slot1, now)
        self.decide(supervisor, execution1)
        wait_until("journey preferred", lambda: scalar(EXECUTION_DSN,
            "SELECT tank_id FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL",
            (uuid.UUID(execution1),)) == uuid.UUID(self.ids["tank_journey"]))
        self.submit_and_complete(student1, supervisor, execution1)

        unavailability = self.disable(supervisor, tank=self.ids["tank_journey"])
        student2 = uid(); self.seed_identity(student2, "ALUMNO", academic=True)
        slot2 = self.create_slot(utcnow() + timedelta(minutes=11), utcnow())
        appointment2 = self.schedule(student2, slot2, "JOURNEY", utcnow())
        execution2 = self.register_arrival(supervisor, appointment2, slot2, utcnow())
        self.decide(supervisor, execution2)
        wait_until("journey fallback", lambda: scalar(EXECUTION_DSN,
            "SELECT tank_id FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL",
            (uuid.UUID(execution2),)) in (uuid.UUID(self.ids["tank_normal1"]), uuid.UUID(self.ids["tank_normal2"])))
        self.submit_and_complete(student2, supervisor, execution2)
        self.restore(supervisor, unavailability)

    def scenario_03_immuno_cleaning(self, supervisor):
        print("[03] IMMUNO physical cleaning + auto completion")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=12), now)
        appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
        execution = self.register_arrival(supervisor, appointment, slot, now)
        self.decide(supervisor, execution)
        wait_until("immuno in progress", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "IN_PROGRESS")
        self.submit_and_complete(student, supervisor, execution)
        cleaning = wait_until("cleaning started", lambda: rows(EXECUTION_DSN,
            "SELECT cleaning_period_id,starts_at,expected_ends_at FROM cleaning_periods WHERE wash_execution_id=%s",
            (uuid.UUID(execution),)) or None)[0]
        released = scalar(EXECUTION_DSN,
            "SELECT released_at FROM resource_assignments WHERE wash_execution_id=%s ORDER BY assigned_at DESC LIMIT 1",
            (uuid.UUID(execution),))
        assert cleaning[1] == released, "cleaning must start at actual releasedAt"
        assert cleaning[2] - cleaning[1] == timedelta(hours=3), "cleaning must last exactly 3h"
        # Test-only clock shift: keep the 3h invariant and let the real worker finish it.
        execute(EXECUTION_DSN, "UPDATE cleaning_periods SET starts_at=now()-interval '4 hours', expected_ends_at=now()-interval '1 hour', completed_at=NULL, updated_at=now() WHERE cleaning_period_id=%s", (cleaning[0],))
        wait_until("cleaning auto completion", lambda: scalar(EXECUTION_DSN,
            "SELECT completed_at IS NOT NULL FROM cleaning_periods WHERE cleaning_period_id=%s", (cleaning[0],)) is True, timeout=20)
        wait_until("cleaning projected by appointments", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT count(*) FROM cleaning_period_projection WHERE wash_execution_id=%s", (uuid.UUID(execution),)) >= 1)

    def scenario_04_cancel_immuno_without_use(self):
        print("[04] IMMUNO cancellation releases preventive reservation")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        base = utcnow(); first = self.create_slot(base + timedelta(hours=1), base)
        appointment = self.schedule(student, first, "IMMUNOCOMPROMISED", base)
        version = int(scalar(APPOINTMENTS_DSN, "SELECT version FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)))
        self.command("appointments", "CancelAppointment", {
            "appointmentId": appointment, "expectedVersion": version, "reason": "PR4_E2E",
        }, self.account_actor(student), occurred_at=base + timedelta(minutes=1))
        wait_until("immuno appointment cancelled", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "CANCELLED")
        second = self.create_slot(base + timedelta(hours=2), base + timedelta(minutes=2))
        replacement = self.schedule(student, second, "IMMUNOCOMPROMISED", base + timedelta(minutes=2))
        assert replacement
        assert scalar(EXECUTION_DSN, "SELECT count(*) FROM cleaning_periods WHERE wash_execution_id IN (SELECT wash_execution_id FROM wash_executions WHERE appointment_id=%s)", (uuid.UUID(appointment),)) == 0

    def scenario_05_missed_immuno_without_use(self):
        print("[05] MISSED IMMUNO releases preventive reservation")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        base = utcnow(); start = base + timedelta(minutes=30)
        first = self.create_slot(start, base)
        appointment = self.schedule(student, first, "IMMUNOCOMPROMISED", base)
        version = int(scalar(APPOINTMENTS_DSN, "SELECT version FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)))
        missed_at = start + timedelta(minutes=11)
        self.command("appointments", "MarkAppointmentAsMissed", {
            "appointmentId": appointment, "expectedVersion": version,
        }, self.system_actor("appointments"), occurred_at=missed_at, producer="appointments")
        wait_until("appointment missed", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "MISSED")
        second = self.create_slot(start + timedelta(hours=2), missed_at)
        replacement = self.schedule(student, second, "IMMUNOCOMPROMISED", missed_at)
        assert replacement

    def scenario_06_entry_rejected_immuno(self, supervisor):
        print("[06] ENTRY_REJECTED IMMUNO without physical cleaning")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); start = now + timedelta(minutes=10)
        slot = self.create_slot(start, now)
        appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
        execution = self.register_arrival(supervisor, appointment, slot, now)
        self.decide(supervisor, execution, "REJECTED")
        wait_until("entry rejected", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "ENTRY_REJECTED")
        wait_until("appointment entry rejected", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "ENTRY_REJECTED")
        assert scalar(EXECUTION_DSN, "SELECT count(*) FROM cleaning_periods WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == 0

    def scenario_07_pending_then_initial(self, supervisor):
        print("[07] authorized without resource -> pending -> INITIAL")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now)
        appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
        unavailable = self.disable(supervisor, tank=self.ids["tank_immuno"])
        execution = self.register_arrival(supervisor, appointment, slot, now)
        self.decide(supervisor, execution)
        wait_until("pending initial", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
        wait_until("entryAuthorizedAt while appointment remains scheduled", lambda: rows(APPOINTMENTS_DSN,
            "SELECT status,entry_authorized_at FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),))[0][0] == "SCHEDULED" and rows(APPOINTMENTS_DSN,
            "SELECT status,entry_authorized_at FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),))[0][1] is not None)
        self.restore(supervisor, unavailable)
        now2 = utcnow()
        self.command("wash-execution", "ResolvePendingReassignment", {
            "washExecutionId": execution, "cabinId": self.ids["cabin_immuno"], "tankId": self.ids["tank_immuno"],
            "resolvedByAccountId": supervisor, "resolvedAt": iso(now2), "expectedVersion": self.execution_version(execution),
        }, self.account_actor(supervisor), occurred_at=now2)
        wait_until("initial assignment after pending", lambda: scalar(EXECUTION_DSN,
            "SELECT assignment_type FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL", (uuid.UUID(execution),)) == "INITIAL")

    def scenario_08_failure_reassignment(self, supervisor):
        print("[08] resource failure -> pending -> REASSIGNMENT")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now)
        appointment = self.schedule(student, slot, "NORMAL", now)
        execution = self.register_arrival(supervisor, appointment, slot, now); self.decide(supervisor, execution)
        wait_until("normal assigned", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "IN_PROGRESS")
        assigned_tank = str(scalar(EXECUTION_DSN, "SELECT tank_id FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL", (uuid.UUID(execution),)))
        assigned_cabin = str(scalar(EXECUTION_DSN, "SELECT cabin_id FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL", (uuid.UUID(execution),)))
        unavailable = self.disable(supervisor, tank=assigned_tank, detected=execution)
        wait_until("execution pending after failure", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
        alternate = self.ids["tank_normal2"] if assigned_tank != self.ids["tank_normal2"] else self.ids["tank_normal1"]
        alt_cabin = self.ids["cabin_normal2"] if alternate == self.ids["tank_normal2"] else self.ids["cabin_normal1"]
        now2 = utcnow()
        self.command("wash-execution", "ResolvePendingReassignment", {
            "washExecutionId": execution, "cabinId": alt_cabin, "tankId": alternate,
            "resolvedByAccountId": supervisor, "resolvedAt": iso(now2), "expectedVersion": self.execution_version(execution),
        }, self.account_actor(supervisor), occurred_at=now2)
        wait_until("resource reassigned", lambda: scalar(EXECUTION_DSN,
            "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=%s AND assignment_type='REASSIGNMENT' AND released_at IS NULL", (uuid.UUID(execution),)) == 1)
        self.restore(supervisor, unavailable)

    def scenario_09_failure_no_alternative_cancel(self, supervisor):
        print("[09] no alternative -> clinic cancellation")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now)
        appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
        execution = self.register_arrival(supervisor, appointment, slot, now); self.decide(supervisor, execution)
        wait_until("immuno started", lambda: scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "IN_PROGRESS")
        unavailable = self.disable(supervisor, tank=self.ids["tank_immuno"], detected=execution)
        wait_until("immuno pending after failure", lambda: scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "PENDING_REASSIGNMENT")
        now2 = utcnow()
        self.command("wash-execution", "CancelWashExecutionByClinic", {
            "washExecutionId": execution, "cancellationSubreason": "CAPACITY_LOSS", "cancellationReason": "PR4_E2E",
            "cancelledByAccountId": supervisor, "cancelledAt": iso(now2), "expectedVersion": self.execution_version(execution),
        }, self.account_actor(supervisor), occurred_at=now2)
        wait_until("execution clinic cancelled", lambda: scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) == "CANCELLED")
        wait_until("appointment clinic cancelled", lambda: scalar(APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s", (uuid.UUID(appointment),)) == "CANCELLED")
        self.restore(supervisor, unavailable)

    def scenario_10_future_capacity_loss(self, supervisor):
        print("[10] operational capacity loss propagates to future appointments")
        base = utcnow(); slot = self.create_slot(base + timedelta(hours=1), base)
        students = []
        for _ in range(2):
            student = uid(); students.append(student); self.seed_identity(student, "ALUMNO", academic=True)
            self.schedule(student, slot, "NORMAL", base)
        before = scalar(APPOINTMENTS_DSN, "SELECT count(*) FROM appointments WHERE appointment_time_slot_id=%s AND status='SCHEDULED'", (uuid.UUID(slot),))
        unavailable = [
            self.disable(supervisor, tank=self.ids["tank_normal1"]),
            self.disable(supervisor, tank=self.ids["tank_normal2"]),
            self.disable(supervisor, tank=self.ids["tank_journey"]),
        ]
        wait_until("future appointments reconciled for capacity loss", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT count(*) FROM appointments WHERE appointment_time_slot_id=%s AND status='SCHEDULED'", (uuid.UUID(slot),)) < before, timeout=20)
        for item in unavailable:
            self.restore(supervisor, item)

    def scenario_11_cross_slot_no_capacity_leak(self, supervisor):
        print("[11] delayed execution does not consume next slot")
        target_tank = self.ids["tank_normal1"]; target_cabin = self.ids["cabin_normal1"]
        old_execution = uuid.UUID(uid()); old_slot = uuid.UUID(uid()); old_appointment = uuid.UUID(uid()); assignment = uuid.UUID(uid()); now = utcnow()
        execute(EXECUTION_DSN, "INSERT INTO wash_executions(wash_execution_id,appointment_id,appointment_type,status,arrived_at,started_at,version,created_at,updated_at) VALUES(%s,%s,'NORMAL','IN_PROGRESS',%s,%s,1,%s,%s)", (old_execution, old_appointment, now - timedelta(hours=2), now - timedelta(hours=2), now, now))
        execute(EXECUTION_DSN, "INSERT INTO resource_assignments(resource_assignment_id,wash_execution_id,appointment_time_slot_id,cabin_id,tank_id,assignment_type,assigned_at,created_at) VALUES(%s,%s,%s,%s,%s,'INITIAL',%s,%s)", (assignment, old_execution, old_slot, uuid.UUID(target_cabin), uuid.UUID(target_tank), now - timedelta(hours=2), now - timedelta(hours=2)))
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        slot = self.create_slot(now + timedelta(minutes=10), now); appointment = self.schedule(student, slot, "NORMAL", now)
        execution = self.register_arrival(supervisor, appointment, slot, now); self.decide(supervisor, execution)
        wait_until("new slot assignment despite delayed prior execution", lambda: scalar(EXECUTION_DSN,
            "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=%s AND released_at IS NULL", (uuid.UUID(execution),)) == 1)
        execute(EXECUTION_DSN, "DELETE FROM resource_assignments WHERE resource_assignment_id=%s", (assignment,)); execute(EXECUTION_DSN, "DELETE FROM wash_executions WHERE wash_execution_id=%s", (old_execution,))

    def scenario_12_cleaning_actual_release(self):
        print("[12] cleaning interval is anchored to releasedAt, not slot.endsAt")
        row = rows(EXECUTION_DSN, """SELECT c.starts_at,c.expected_ends_at,a.released_at,s.ends_at
            FROM cleaning_periods c JOIN resource_assignments a ON a.resource_assignment_id=c.resource_assignment_id
            LEFT JOIN appointment_time_slot_projection s ON s.appointment_time_slot_id=a.appointment_time_slot_id
            ORDER BY c.created_at LIMIT 1""")
        assert row, "scenario 03 must create a cleaning period"
        starts, ends, released, slot_end = row[0]
        # Scenario 03 shifts its period only after first validating releasedAt; prove duration remains physical.
        assert ends - starts == timedelta(hours=3)
        assert released is not None and slot_end is not None

    def scenario_13_last_capacity_race(self, supervisor):
        print("[13] concurrent race for last Tank+slot capacity")
        # Isolate NORMAL to one tank by disabling all compatible alternatives.
        disabled = [self.disable(supervisor, tank=self.ids["tank_normal2"]), self.disable(supervisor, tank=self.ids["tank_journey"])]
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now)
        old_execution = uuid.UUID(uid()); old_appointment = uuid.UUID(uid()); assignment = uuid.UUID(uid())
        execute(EXECUTION_DSN, "INSERT INTO wash_executions(wash_execution_id,appointment_id,appointment_type,status,arrived_at,started_at,version,created_at,updated_at) VALUES(%s,%s,'NORMAL','IN_PROGRESS',%s,%s,1,%s,%s)", (old_execution, old_appointment, now, now, now, now))
        execute(EXECUTION_DSN, "INSERT INTO resource_assignments(resource_assignment_id,wash_execution_id,appointment_time_slot_id,cabin_id,tank_id,assignment_type,assigned_at,created_at) VALUES(%s,%s,%s,%s,%s,'INITIAL',%s,%s)", (assignment, old_execution, uuid.UUID(slot), uuid.UUID(self.ids["cabin_normal1"]), uuid.UUID(self.ids["tank_normal1"]), now, now))
        executions = []
        for _ in range(2):
            student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
            appointment = self.schedule(student, slot, "NORMAL", now)
            executions.append(self.register_arrival(supervisor, appointment, slot, now))
        threads = [threading.Thread(target=self.decide, args=(supervisor, execution)) for execution in executions]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        wait_until("race settled", lambda: all(scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(e),)) in ("IN_PROGRESS", "PENDING_REASSIGNMENT") for e in executions))
        statuses = [scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(e),)) for e in executions]
        assert statuses.count("IN_PROGRESS") == 1 and statuses.count("PENDING_REASSIGNMENT") == 1, statuses
        active = scalar(EXECUTION_DSN, "SELECT count(*) FROM resource_assignments WHERE tank_id=%s AND appointment_time_slot_id=%s AND released_at IS NULL", (uuid.UUID(self.ids["tank_normal1"]), uuid.UUID(slot)))
        assert active == 2, f"capacity=2 must never be exceeded, got {active}"
        execute(EXECUTION_DSN, "DELETE FROM resource_assignments WHERE resource_assignment_id=%s", (assignment,)); execute(EXECUTION_DSN, "DELETE FROM wash_executions WHERE wash_execution_id=%s", (old_execution,))
        for item in disabled: self.restore(supervisor, item)

    def scenario_14_assign_vs_disable(self, supervisor):
        print("[14] assign vs disable serializes to a valid state")
        student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
        now = utcnow(); slot = self.create_slot(now + timedelta(minutes=10), now); appointment = self.schedule(student, slot, "IMMUNOCOMPROMISED", now)
        execution = self.register_arrival(supervisor, appointment, slot, now)
        unavailability_holder = {}
        t1 = threading.Thread(target=self.decide, args=(supervisor, execution))
        def do_disable(): unavailability_holder["id"] = self.disable(supervisor, tank=self.ids["tank_immuno"], detected=execution)
        t2 = threading.Thread(target=do_disable)
        t1.start(); t2.start(); t1.join(); t2.join()
        unavailability = unavailability_holder["id"]
        wait_until("assign-disable settled", lambda: scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),)) in ("PENDING_REASSIGNMENT", "IN_PROGRESS"))
        status = scalar(EXECUTION_DSN, "SELECT status FROM wash_executions WHERE wash_execution_id=%s", (uuid.UUID(execution),))
        active_on_disabled = scalar(EXECUTION_DSN, "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=%s AND tank_id=%s AND released_at IS NULL", (uuid.UUID(execution), uuid.UUID(self.ids["tank_immuno"])))
        if active_on_disabled:
            assert status == "PENDING_REASSIGNMENT", f"invalid active execution on disabled resource: {status}"
        self.restore(supervisor, unavailability)

    def insert_recovery_job(self, projection, query_type, query_id, operation_id, correlation_id, service_date=None):
        job = uuid.UUID(uid()); now = utcnow()
        execute(EXECUTION_DSN, """INSERT INTO projection_recovery_jobs(recovery_job_id,projection_name,scope,entity_key,service_date,status,cursor,query_attempt,current_query_type,current_query_message_id,current_operation_id,current_correlation_id,created_at,updated_at)
            VALUES(%s,%s,'FULL',NULL,%s,'WAITING_RESULT',NULL,1,%s,%s,%s,%s,%s,%s)""",
            (job, projection, service_date, query_type, uuid.UUID(query_id), uuid.UUID(operation_id), uuid.UUID(correlation_id), now, now))
        execute(EXECUTION_DSN, """INSERT INTO projection_recovery_state(projection_name,source_logical_component_id,readiness_status,last_error,updated_at)
            VALUES(%s,%s,'DEGRADED','PR4_E2E',%s) ON CONFLICT(projection_name) DO UPDATE SET readiness_status='DEGRADED',last_error='PR4_E2E',updated_at=excluded.updated_at""", (projection, projection, now))
        return str(job)

    def scenario_15_projection_recovery(self):
        print("[15] authoritative recovery for Appointments/Admin/Authorization")
        service_date = self.zone.fromutc(utcnow().replace(tzinfo=self.zone)).date() if False else utcnow().astimezone(self.zone).date()
        # Appointments FULL page recovery.
        execute(EXECUTION_DSN, "DELETE FROM appointment_projection")
        execute(EXECUTION_DSN, "DELETE FROM appointment_time_slot_projection")
        qid, op, corr = uid(), uid(), uid()
        self.insert_recovery_job("appointments", "ListAppointmentExecutionContextsPage", qid, op, corr, service_date)
        self.query("appointments", "wash-execution", "ListAppointmentExecutionContextsPage", {
            "serviceDate": str(service_date), "pageSize": 200,
        }, qid, op, corr)
        wait_until("appointments recovery READY", lambda: scalar(EXECUTION_DSN,
            "SELECT readiness_status FROM projection_recovery_state WHERE projection_name='appointments'") == "READY", timeout=20)
        assert scalar(EXECUTION_DSN, "SELECT count(*) FROM appointment_projection") > 0

        # Wash Administration FULL recovery plus concurrent higher-version Event.
        execute(EXECUTION_DSN, "TRUNCATE TABLE administrative_closure_projection,resource_priority_projection,tank_projection,cabin_projection")
        qid, op, corr = uid(), uid(), uid()
        self.insert_recovery_job("wash-administration", "GetWashAdministrationConfiguration", qid, op, corr)
        self.query("wash-administration", "wash-execution", "GetWashAdministrationConfiguration", {}, qid, op, corr)
        # Publish an increment newer than the snapshot; final state must preserve v2.
        now = utcnow(); tank = uuid.UUID(self.ids["tank_normal1"]); cabin = uuid.UUID(self.ids["cabin_normal1"])
        with db(ADMIN_DSN) as conn, conn.cursor() as cur:
            self._admin_outbox(cur, "TankUpdated", tank, 2, {
                "tankId": str(tank), "cabinId": str(cabin), "code": "T-N-1", "name": "Tank N-1",
                "configuredCapacity": 3, "status": "ACTIVE", "version": 2, "occurredAt": iso(now),
            }, now)
        wait_until("wash admin recovery READY", lambda: scalar(EXECUTION_DSN,
            "SELECT readiness_status FROM projection_recovery_state WHERE projection_name='wash-administration'") == "READY", timeout=20)
        wait_until("concurrent admin event preserved", lambda: scalar(EXECUTION_DSN,
            "SELECT source_version FROM tank_projection WHERE tank_id=%s", (tank,)) == 2, timeout=20)

        # Authorization FULL recovery against the real Authorization owner.
        execute(EXECUTION_DSN, "TRUNCATE TABLE authorization_access_projection,authorization_role_projection")
        qid, op, corr = uid(), uid(), uid()
        self.insert_recovery_job("authorization", "ListAccountAuthorizationSnapshotsPage", qid, op, corr)
        self.query("authorization", "wash-execution", "ListAccountAuthorizationSnapshotsPage", {
            "limit": 200, "systemCode": "LAVADO_ULTRASONICO",
        }, qid, op, corr)
        wait_until("authorization recovery READY", lambda: scalar(EXECUTION_DSN,
            "SELECT readiness_status FROM projection_recovery_state WHERE projection_name='authorization'") == "READY", timeout=20)
        assert scalar(EXECUTION_DSN, "SELECT count(*) FROM authorization_role_projection") > 0

    def same_version_and_late_arrival_assertions(self):
        print("[P1] same-version events + late supervisor decision evidence")
        appointment = self.ids["normal_appointment"]
        execution = self.ids["normal_execution"]
        evidence = rows(APPOINTMENTS_DSN, "SELECT arrived_at,entry_authorized_at FROM wash_execution_evidence_projection WHERE appointment_id=%s", (uuid.UUID(appointment),))
        assert evidence and evidence[0][0] is not None and evidence[0][1] is not None
        distinct = scalar(APPOINTMENTS_DSN, "SELECT count(DISTINCT event_type) FROM wash_execution_transition_projection WHERE wash_execution_id=%s", (uuid.UUID(execution),))
        assert distinct >= 2, "distinct same-version facts must not collapse by version"

    def preventive_immuno_symmetry(self):
        print("[P1] symmetric IMMUNO preventive reservation")
        anchor_student = uid(); self.seed_identity(anchor_student, "ALUMNO", academic=True)
        base = utcnow(); anchor_start = base + timedelta(hours=4)
        anchor_slot = self.create_slot(anchor_start, base); self.schedule(anchor_student, anchor_slot, "IMMUNOCOMPROMISED", base)
        offsets = (-3, -2, -1, 1, 2, 3)
        for offset in offsets:
            student = uid(); self.seed_identity(student, "ALUMNO", academic=True)
            candidate_start = anchor_start + timedelta(hours=offset)
            slot = self.create_slot(candidate_start, base)
            before = scalar(APPOINTMENTS_DSN, "SELECT count(*) FROM appointments WHERE student_account_id=%s AND appointment_time_slot_id=%s", (uuid.UUID(student), uuid.UUID(slot)))
            self.command("appointments", "ScheduleAppointment", {
                "studentAccountId": student, "appointmentTimeSlotId": slot,
                "courseSectionId": self.ids[f"section:{student}"], "appointmentType": "IMMUNOCOMPROMISED",
                "instrumentCount": 1, "pieceType": "HIGH_SPEED",
            }, self.account_actor(student), occurred_at=base)
            time.sleep(0.4)
            after = scalar(APPOINTMENTS_DSN, "SELECT count(*) FROM appointments WHERE student_account_id=%s AND appointment_time_slot_id=%s", (uuid.UUID(student), uuid.UUID(slot)))
            assert after == before, f"IMMUNO offset {offset}h must conflict symmetrically"

    def prepare_restart_probe(self):
        row = rows(EXECUTION_DSN, "SELECT message_id FROM outbox_messages WHERE message_type='WashArrivalRegistered' AND status='PUBLISHED' ORDER BY created_at LIMIT 1")
        assert row, "need a published event for restart probe"
        message_id = row[0][0]
        appointment = uuid.UUID(self.ids["normal_appointment"])
        status = scalar(APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s", (appointment,))
        execute(EXECUTION_DSN, "UPDATE outbox_messages SET status='PENDING',published_at=NULL,claim_token=NULL,lease_until=NULL,available_at=now() WHERE message_id=%s", (message_id,))
        execute(EXECUTION_DSN, "CREATE TABLE IF NOT EXISTS pr4_e2e_state(key text PRIMARY KEY,value text NOT NULL)")
        execute(EXECUTION_DSN, "INSERT INTO pr4_e2e_state(key,value) VALUES('restart_probe',%s) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (json.dumps({"messageId": str(message_id), "appointmentId": str(appointment), "status": status}),))

    def run_main(self):
        self.connect(); self.wait_infrastructure()
        service_date = self.seed_admin_owner()
        supervisor = uid(); self.seed_identity(supervisor, "SUPERVISOR_LAVADO")
        self.mark_execution_ready()
        self.scenario_01_normal_complete(supervisor)
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

    def run_restart(self):
        self.connect(); self.wait_infrastructure()
        raw = scalar(EXECUTION_DSN, "SELECT value FROM pr4_e2e_state WHERE key='restart_probe'")
        assert raw, "restart probe state missing"
        probe = json.loads(raw)
        message_id = uuid.UUID(probe["messageId"]); appointment = uuid.UUID(probe["appointmentId"])
        wait_until("pending Outbox recovered after restart", lambda: scalar(EXECUTION_DSN,
            "SELECT status FROM outbox_messages WHERE message_id=%s", (message_id,)) == "PUBLISHED", timeout=20)
        # Re-publication uses the same messageId; Appointments Inbox must keep one durable effect.
        wait_until("duplicate event remains deduplicated", lambda: scalar(APPOINTMENTS_DSN,
            "SELECT count(*) FROM processed_messages WHERE message_id=%s", (message_id,)) == 1, timeout=20)
        assert scalar(APPOINTMENTS_DSN, "SELECT status FROM appointments WHERE appointment_id=%s", (appointment,)) == probe["status"]
        self.close()
        print("restart/outbox/inbox scenario: PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("main", "restart"), required=True)
    args = parser.parse_args()
    harness = Harness()
    if args.phase == "main":
        harness.run_main()
    else:
        harness.run_restart()


if __name__ == "__main__":
    main()
