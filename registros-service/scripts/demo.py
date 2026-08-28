#!/usr/bin/env python3
"""One-shot demo of registros-service. Requires BOTH jornadas-service and
registros-service already running (docker compose up -d in both, then
./mvnw spring-boot:run in both), since registros validates a pre-registro
against a Jornada that only jornadas-service can create, and learns about it
by consuming JornadaPublicada/Cancelada over platform.events.

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


def publish(routing_key, env):
    http("POST", "/exchanges/%2F/platform.commands/publish", {
        "properties": {"content_type": "application/json"},
        "routing_key": routing_key,
        "payload": json.dumps(env),
        "payload_encoding": "string",
    })


def publish_fake_event(routing_key, message_type, aggregate_id, aggregate_version, payload):
    """Publishes directly to platform.events, simulating a jornadas-service Event.
    Used for JornadaFinalizada, which has no manual Command (it's a scheduled worker
    in jornadas-service) -- this exercises registros-service's consumer contract in
    isolation, the same way it would react to the real Event."""
    env = {
        "messageId": str(uuid.uuid4()),
        "messageType": message_type,
        "messageVersion": 1,
        "messageKind": "EVENT",
        "producer": "jornadas",
        "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "correlationId": str(uuid.uuid4()),
        "causationId": str(uuid.uuid4()),
        "operationId": None,
        "idempotencyKey": None,
        "aggregateId": aggregate_id,
        "aggregateVersion": aggregate_version,
        "actor": {"type": "SYSTEM", "accountId": None, "service": "jornadas"},
        "payload": payload,
    }
    http("POST", "/exchanges/%2F/platform.events/publish", {
        "properties": {"content_type": "application/json"},
        "routing_key": routing_key,
        "payload": json.dumps(env),
        "payload_encoding": "string",
    })


def drain(queue, expect=1, timeout=6):
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


def run(title, routing_key, command_env, expect_results=1, expect_events=0):
    print(f"\n--- {title} ---")
    publish(routing_key, command_env)
    results = drain("demo.results.bff", expect=expect_results)
    for r in results:
        print(f"  Result [{r['messageType']}]: {r['payload']}")
    # jornadas commands publish their Event to demo.events.jornadas (jornadas-service's own
    # observer queue), registros commands to demo.events.registros.
    events_queue = "demo.events.jornadas" if routing_key == "jornadas" else "demo.events.registros"
    events = drain(events_queue, expect=expect_events) if expect_events else []
    for e in events:
        print(f"  Event  [{e['messageType']}]: {e['payload']}")
    return results, events


def reset_local_state():
    import subprocess
    print("--- Reiniciando estado local (jornadas_db + registros_db) ---")
    subprocess.run([
        "docker", "exec", "jornadas-service-postgres-1", "psql", "-U", "jornadas_service", "-d", "jornadas_db",
        "-c", "TRUNCATE jornada, tipo_jornada_semestre_permitido, tipo_jornada RESTART IDENTITY CASCADE;",
    ], check=True)
    subprocess.run([
        "docker", "exec", "registros-service-postgres-1", "psql", "-U", "registros_service", "-d", "registros_db",
        "-c", "TRUNCATE registro, jornada_semestre_permitido_proyeccion, jornada_proyeccion RESTART IDENTITY CASCADE;",
    ], check=True)


def purge_queues():
    for q in ("demo.results.bff", "demo.events.jornadas", "demo.events.registros",
              "commands.jornadas.dlq", "commands.registros.dlq", "events.registros.dlq"):
        http("DELETE", f"/queues/%2F/{q}/contents")


def main():
    reset_local_state()
    purge_queues()

    # 1) Set up a Jornada with cupo=1 via jornadas-service, so we can demo both
    #    a successful pre-registro AND a "sin cupo" rejection with just 2 Alumnos.
    results, _ = run(
        "0a) [jornadas] ConfigurarTipoJornada -> Externa, semestres 6-9",
        "jornadas",
        envelope("ConfigurarTipoJornada", {
            "tipoJornadaId": None, "nombre": "Externa", "semestresPermitidos": [6, 7, 8, 9],
        }),
    )
    tipo_jornada_id = results[0]["payload"]["data"]["tipoJornadaId"]

    fecha = (datetime.date.today() + datetime.timedelta(days=10)).isoformat()
    fecha_limite = (datetime.date.today() + datetime.timedelta(days=5)).isoformat()
    results, jornada_events = run(
        "0b) [jornadas] PublicarJornada -> cupoTotal=1 (para demostrar 'sin cupo')",
        "jornadas",
        envelope("PublicarJornada", {
            "tipoJornadaId": tipo_jornada_id,
            "nombre": "Jornada Externa Demo Registros",
            "fecha": fecha,
            "horaInicio": "09:00",
            "horaFin": "13:00",
            "lugar": "Centro de Salud Tepeaca",
            "cupoTotal": 1,
            "fechaLimiteDocumentos": fecha_limite,
        }),
        expect_events=1,  # drained so it doesn't linger in demo.events.jornadas for later steps
    )
    jornada_id = results[0]["payload"]["data"]["jornadaId"]
    jornada_version = results[0]["payload"]["data"]["version"]

    # registros-service needs a moment to consume JornadaPublicada and build its projection.
    print("\n(esperando a que registros-service consuma JornadaPublicada...)")
    time.sleep(1.5)

    alumno_1 = str(uuid.uuid4())
    alumno_2 = str(uuid.uuid4())

    results, events = run(
        "1) [registros] CrearRegistro -> Alumno 1 (hay cupo=1)",
        "registros",
        envelope("CrearRegistro", {"jornadaId": jornada_id, "alumnoId": alumno_1, "semestreAlumno": 7}),
        expect_events=1,
    )
    registro_1_id = results[0]["payload"]["data"]["registroId"]

    run(
        "2) [registros] CrearRegistro -> Alumno 2 (ya no hay cupo, debe rechazarse)",
        "registros",
        envelope("CrearRegistro", {"jornadaId": jornada_id, "alumnoId": alumno_2, "semestreAlumno": 8}),
    )

    run(
        "3) [registros] CrearRegistro -> Alumno 1 otra vez (ya tiene un Registro activo, debe rechazarse)",
        "registros",
        envelope("CrearRegistro", {"jornadaId": jornada_id, "alumnoId": alumno_1, "semestreAlumno": 7}),
    )

    run(
        "4) [registros] ConfirmarRegistro -> Pasante confirma al Alumno 1",
        "registros",
        envelope("ConfirmarRegistro", {}, aggregate_id=registro_1_id),
        expect_events=1,
    )

    # 5) [jornadas] EditarJornada -> sube cupoTotal de 1 a 5 y mueve la fecha. Antes del fix,
    #    JornadaEditada solo llevaba camposModificados y la proyeccion de registros nunca subia
    #    el cupo real, asi que Alumno 2 seguia rechazado para siempre.
    nueva_fecha = (datetime.date.fromisoformat(fecha) + datetime.timedelta(days=1)).isoformat()
    results, jornada_events = run(
        "5) [jornadas] EditarJornada -> cupoTotal=5, fecha+1 dia",
        "jornadas",
        envelope("EditarJornada", {"cupoTotal": 5, "fecha": nueva_fecha},
                 aggregate_id=jornada_id, aggregate_version=jornada_version),
        expect_events=1,
    )
    edited_payload = jornada_events[0]["payload"]
    assert edited_payload["cupoTotal"] == 5 and edited_payload["fecha"] == nueva_fecha, \
        f"JornadaEditada debe llevar el snapshot completo: {edited_payload}"
    time.sleep(1.0)

    run(
        "6) [registros] CrearRegistro -> Alumno 2 otra vez (la edicion abrio cupo, ahora debe aceptarse)",
        "registros",
        envelope("CrearRegistro", {"jornadaId": jornada_id, "alumnoId": alumno_2, "semestreAlumno": 8}),
        expect_events=1,
    )

    run(
        "7) [jornadas] CancelarJornada -> debe cascadear a RegistroCanceladoPorJornadaCancelada",
        "jornadas",
        envelope("CancelarJornada", {"motivo": "Falta de instrumental disponible"}, aggregate_id=jornada_id),
        expect_events=1,
    )
    time.sleep(1.0)
    registro_events = drain("demo.events.registros", expect=2, timeout=4)
    for e in registro_events:
        print(f"  Event  [{e['messageType']}]: {e['payload']}")

    # 8) JornadaFinalizada has no manual Command in jornadas-service (it's a scheduled
    #    worker), so simulate the Event directly to verify registros-service's consumer:
    #    the projection must move to FINALIZADA and block new CrearRegistro.
    print("\n--- 8) [jornadas] JornadaFinalizada (simulado) -> debe bloquear nuevos CrearRegistro ---")
    results, _ = run(
        "8a) [jornadas] PublicarJornada -> segunda Jornada para probar finalizacion",
        "jornadas",
        envelope("PublicarJornada", {
            "tipoJornadaId": tipo_jornada_id,
            "nombre": "Jornada Para Finalizar",
            "fecha": fecha,
            "horaInicio": "09:00",
            "horaFin": "13:00",
            "lugar": "Centro de Salud Tepeaca",
            "cupoTotal": 10,
            "fechaLimiteDocumentos": fecha_limite,
        }),
    )
    jornada_2_id = results[0]["payload"]["data"]["jornadaId"]
    jornada_2_version = results[0]["payload"]["data"]["version"]
    time.sleep(1.0)

    publish_fake_event("jornadas.jornada.finalizada", "JornadaFinalizada",
                        jornada_2_id, jornada_2_version + 1, {"jornadaId": jornada_2_id})
    time.sleep(1.0)

    run(
        "8b) [registros] CrearRegistro -> debe rechazarse, la Jornada ya no esta PUBLICADA",
        "registros",
        envelope("CrearRegistro", {"jornadaId": jornada_2_id, "alumnoId": str(uuid.uuid4()), "semestreAlumno": 7}),
    )

    print("\n--- Estado final ---")
    import subprocess
    subprocess.run([
        "docker", "exec", "registros-service-postgres-1", "psql", "-U", "registros_service", "-d", "registros_db",
        "-c", "select registro_id, alumno_id, estado, version from registro order by created_at;",
    ])
    subprocess.run([
        "docker", "exec", "registros-service-postgres-1", "psql", "-U", "registros_service", "-d", "registros_db",
        "-c", "select jornada_id, cupo_total, cupo_disponible, estado from jornada_proyeccion;",
    ])
    print("\nListo. Abre http://localhost:15672 (guest/guest) -> Queues -> "
          "demo.results.bff / demo.events.registros para verlo tal cual llego por RabbitMQ.")


if __name__ == "__main__":
    main()
