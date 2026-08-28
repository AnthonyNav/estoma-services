#!/usr/bin/env python3
"""One-shot demo of jornadas-service: publishes the 4 commands in sequence
against the running RabbitMQ (guest/guest, localhost:15672) and prints the
Result + Event each one produces. Requires the app already running
(docker compose up -d && JORNADAS_DB_URL=... ./mvnw spring-boot:run) and
consumes from demo.results.bff / demo.events.jornadas, declared
automatically by DemoObserverTopologyConfig on startup.

Usage: python3 scripts/demo.py
"""
import json
import time
import uuid
import base64
import datetime
import urllib.request

RABBIT_API = "http://localhost:15672/api"
AUTH = "Basic " + base64.b64encode(b"guest:guest").decode()


def http(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{RABBIT_API}{path}", data=data, method=method)
    req.add_header("Authorization", AUTH)
    req.add_header("content-type", "application/json")
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def envelope(message_type, payload, aggregate_id=None, aggregate_version=None):
    return {
        "messageId": str(uuid.uuid4()),
        "messageType": message_type,
        "messageVersion": 1,
        "messageKind": "COMMAND",
        "producer": "bff",
        "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "correlationId": str(uuid.uuid4()),
        "causationId": None,
        "operationId": str(uuid.uuid4()),
        "idempotencyKey": str(uuid.uuid4()),
        "aggregateId": aggregate_id,
        "aggregateVersion": aggregate_version,
        "actor": {"type": "ACCOUNT", "accountId": str(uuid.uuid4()), "service": None},
        "payload": payload,
    }


def publish(env):
    http("POST", "/exchanges/%2F/platform.commands/publish", {
        "properties": {"content_type": "application/json"},
        "routing_key": "jornadas",
        "payload": json.dumps(env),
        "payload_encoding": "string",
    })


def drain(queue, expect=1, timeout=5):
    deadline = time.time() + timeout
    messages = []
    while time.time() < deadline and len(messages) < expect:
        got = http("POST", f"/queues/%2F/{queue}/get", {
            "count": expect - len(messages), "ackmode": "ack_requeue_false", "encoding": "auto",
        })
        for m in got:
            messages.append(json.loads(m["payload"]))
        if len(messages) < expect:
            time.sleep(0.3)
    return messages


def run(title, command_env, expect_events=0):
    print(f"\n--- {title} ---")
    publish(command_env)
    results = drain("demo.results.bff", expect=1)
    for r in results:
        print(f"  Result [{r['messageType']}]: {r['payload']}")
    events = drain("demo.events.jornadas", expect=expect_events) if expect_events else []
    for e in events:
        print(f"  Event  [{e['messageType']}]: {e['payload']}")
    return results, events


def reset_local_state():
    """Truncates jornadas_db so the demo starts from a known, empty state every run.
    Local dev only -- never point this at anything but the docker-compose Postgres."""
    import subprocess
    print("--- Reiniciando estado local (jornadas_db) ---")
    subprocess.run([
        "docker", "exec", "jornadas-service-postgres-1", "psql",
        "-U", "jornadas_service", "-d", "jornadas_db",
        "-c", "TRUNCATE jornada, tipo_jornada_semestre_permitido, tipo_jornada RESTART IDENTITY CASCADE;",
    ], check=True)


def purge_observer_queues():
    for q in ("demo.results.bff", "demo.events.jornadas", "commands.jornadas.dlq"):
        http("DELETE", f"/queues/%2F/{q}/contents")


def main():
    reset_local_state()
    purge_observer_queues()
    results, _ = run(
        "1) ConfigurarTipoJornada -> crea el tipo 'Externa' con semestres 6-9",
        envelope("ConfigurarTipoJornada", {
            "tipoJornadaId": None, "nombre": "Externa", "semestresPermitidos": [6, 7, 8, 9],
        }),
    )
    tipo_jornada_id = results[0]["payload"]["data"]["tipoJornadaId"]

    results, events = run(
        "2) PublicarJornada -> publica una Jornada de ese tipo",
        envelope("PublicarJornada", {
            "tipoJornadaId": tipo_jornada_id,
            "nombre": "Jornada Externa Septiembre",
            "fecha": "2026-09-20",
            "horaInicio": "09:00",
            "horaFin": "13:00",
            "lugar": "Clinica Smile",
            "cupoTotal": 15,
            "fechaLimiteDocumentos": "2026-09-15",
            "descripcion": "Jornada de demo",
        }),
        expect_events=1,
    )
    jornada_id = results[0]["payload"]["data"]["jornadaId"]
    version = results[0]["payload"]["data"]["version"]

    results, events = run(
        "3) EditarJornada -> sube el cupo a 25 y mueve la fecha al 2026-09-27",
        envelope("EditarJornada", {"cupoTotal": 25, "fecha": "2026-09-27"},
                 aggregate_id=jornada_id, aggregate_version=version),
        expect_events=1,
    )
    version = results[0]["payload"]["data"]["version"]
    edited_payload = events[0]["payload"]
    assert edited_payload["cupoTotal"] == 25, \
        f"JornadaEditada debe llevar el cupoTotal nuevo, no solo camposModificados: {edited_payload}"
    assert edited_payload["fecha"] == "2026-09-27", \
        f"JornadaEditada debe llevar la fecha nueva, no solo camposModificados: {edited_payload}"
    print("  OK: JornadaEditada trae la fotografia completa (cupoTotal y fecha nuevos), "
          "no solo los nombres de los campos modificados -- Registros/BFF pueden proyectarla sin Query adicional.")

    run(
        "3b) EditarJornada otra vez con la MISMA version -> debe rechazarse (conflicto de concurrencia)",
        envelope("EditarJornada", {"cupoTotal": 99}, aggregate_id=jornada_id, aggregate_version=version - 1),
    )

    results, events = run(
        "4) CancelarJornada -> cancela con motivo",
        envelope("CancelarJornada", {"motivo": "Falta de instrumental disponible"}, aggregate_id=jornada_id),
        expect_events=1,
    )

    print("\n--- Estado final en la base de datos ---")
    import subprocess
    subprocess.run([
        "docker", "exec", "jornadas-service-postgres-1", "psql",
        "-U", "jornadas_service", "-d", "jornadas_db",
        "-c", "select jornada_id, nombre, estado, cupo_total, version from jornada order by created_at;",
    ])
    print("\nListo. Abre http://localhost:15672 (guest/guest) -> Queues -> demo.results.bff / "
          "demo.events.jornadas para verlo tal cual llego por RabbitMQ.")


if __name__ == "__main__":
    main()
