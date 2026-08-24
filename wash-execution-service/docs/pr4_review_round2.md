# Wash Execution — PR4 Review Round 2

**Estado:** requiere correcciones antes de verificación final
**PR revisado:** `#41 test(wash-execution): PR4 integration hardening`
**Head revisado:** `941301a31361d07dc7f24ad1b7fc48223b2aa56a`
**Base real de la rama:** `develop@07337bd15176dc69d79faaf5c895d280c3a8b5d0`

## 1. Resultado general

La reconstrucción de la rama mejoró correctamente respecto de la primera revisión:

```text
develop...PR4
→ ahead_by = 1
→ behind_by = 0
```

El stack antiguo de PR1–PR3 ya no forma parte del delta real contra `develop`.

Sin embargo, PR4 **todavía no está listo para merge**. Los cambios actuales son principalmente fixes de integración/ejecución en Appointments y correcciones de persistencia/compilación de Wash Execution; aún falta el objetivo central de PR4: un E2E real del bounded context y sus escenarios completos.

---

# 2. P0 — retarget del Pull Request

La rama está construida correctamente sobre el `develop` actual, pero el PR #41 todavía tiene como base:

```text
wash-execution/feature/pr3-messaging-projections-recovery
```

Debe cambiarse a:

```text
develop
```

Mientras no se haga:

- GitHub muestra decenas de commits/archivos heredados que no pertenecen al delta actual;
- el workflow del PR evalúa contra una base obsoleta;
- hacer merge del PR no apunta al branch de integración esperado.

Después del retarget verificar:

```text
ahead_by = 1
behind_by = 0
mergeable = true
```

y revisar de nuevo `Files changed`.

---

# 3. P0 — routing de Events de Appointments no coincide con Execution

Los payloads cross-service fueron mejorados, pero la ruta física de Events sigue siendo incompatible.

`appointments-service` actualmente deriva Events con una transformación genérica equivalente a:

```text
AppointmentTimeSlotCreated
→ appointments.appointment-time-slot-created

AppointmentScheduled
→ appointments.appointment-scheduled

AppointmentCancelled
→ appointments.appointment-cancelled

AppointmentMissed
→ appointments.appointment-missed
```

La convención transversal canónica es:

```text
<context>.<subject>.<event>
```

Y `wash-execution-service` está correctamente bindeado a:

```text
appointments.appointment-time-slot.created
appointments.appointment-time-slot.cancelled
appointments.appointment.scheduled
appointments.appointment.cancelled
appointments.appointment.missed
```

Por lo tanto, hoy los Events de Appointments **no llegan** a `events.wash-execution` por el canal incremental normal.

## Corrección requerida

No usar una conversión CamelCase → kebab como routing genérico para Events.

Introducir un resolver/mapping explícito y contractual, al menos:

```text
AppointmentTimeSlotCreated
→ appointments.appointment-time-slot.created

AppointmentTimeSlotCancelled
→ appointments.appointment-time-slot.cancelled

AppointmentScheduled
→ appointments.appointment.scheduled

AppointmentCancelled
→ appointments.appointment.cancelled

AppointmentMissed
→ appointments.appointment.missed
```

Idealmente centralizar todas las routing keys canónicas de Events producidos por Appointments en un único resolver testeable.

No cambiar los bindings correctos de Execution para adaptarlos a rutas incorrectas del productor.

---

# 4. P0 — contract tests del productor todavía incompletos

Los fixes de contrato implementados en Appointments sí van en la dirección correcta:

- `AppointmentTimeSlot` conserva `calendarId`;
- existe migración aditiva/backfill de `appointment_time_slots.calendar_id`;
- `AppointmentTimeSlotCreated` contiene contexto temporal + `calendarId`;
- `AppointmentScheduled` contiene el contexto operativo requerido por Execution;
- `AppointmentCancelled`, `AppointmentMissed` y `AppointmentTimeSlotCancelled` usan identificadores wire canónicos;
- `GetAppointmentExecutionContext` y `ListAppointmentExecutionContextsPage` incluyen `timeSlot.calendarId`.

Pero la cobertura RabbitMQ actual comprueba principalmente:

- topología;
- Command interno;
- Query/Result de recovery;
- retry/DLQ.

Faltan contract tests del productor que publiquen realmente mediante `OutboxRelay` y demuestren:

```text
AppointmentTimeSlotCreated
→ routing key exacta
→ calendarId presente
→ appointmentTimeSlotId presente
→ contexto temporal presente

AppointmentScheduled
→ routing key exacta
→ studentAccountId
→ appointmentTimeSlotId
→ courseSectionId
→ appointmentType
→ instrumentCount
→ pieceType
→ status

AppointmentCancelled
AppointmentMissed
AppointmentTimeSlotCancelled
→ routing key exacta + identificadores canónicos
```

Estos tests deben bindear una queue temporal a las routing keys canónicas exactas; un wildcard `appointments.#` no detecta este tipo de error de naming.

---

# 5. P0 — PR4 todavía no contiene E2E distribuido

La definición de PR4 exige integrar, como mínimo:

```text
appointments-service
wash-administration-service
wash-execution-service
RabbitMQ
PostgreSQL separado por servicio
```

El estado actual continúa ejecutando tests de cada servicio de forma aislada.

Ejemplos actuales en Wash Execution:

```text
WashExecutionCommandIT
→ autowirea WashExecutionCommandUseCase
→ siembra appointment_projection / authorization / recursos directamente
→ usa únicamente wash_execution_db

CommandMessagingIT
→ levanta wash-execution-service + su PostgreSQL + RabbitMQ
→ siembra proyecciones locales directamente
→ no ejecuta appointments-service ni wash-administration-service reales
```

Esos tests son útiles y deben conservarse, pero validan PR2/PR3, no PR4.

## Harness requerido

Crear un harness que levante conjuntamente:

```text
RabbitMQ compartido
PostgreSQL Appointments
PostgreSQL Wash Administration
PostgreSQL Wash Execution
appointments-service
wash-administration-service
wash-execution-service
```

Authorization:

```text
preferible: authorization-service real
aceptable: fixture contractual RabbitMQ fiel al owner
```

No sustituir el camino distribuido escribiendo directamente en las proyecciones de Execution/Appointments para los escenarios E2E principales.

Camino a demostrar:

```text
Command por RabbitMQ
→ owner real
→ DB del owner
→ Transactional Outbox
→ RabbitMQ
→ consumer real
→ Consumer Inbox/proyección local
→ siguiente Command
```

---

# 6. P0 — faltan los 15 escenarios contractuales de PR4

No considerar PR4 terminado hasta automatizar los escenarios de `execution_plan.md`:

1. NORMAL completo;
2. JOURNEY preferred;
3. JOURNEY fallback;
4. IMMUNO + reserva preventiva + CleaningPeriod;
5. cancelar IMMUNO sin uso;
6. MISSED IMMUNO sin uso;
7. ENTRY_REJECTED IMMUNO sin uso;
8. entrada autorizada sin recurso → pending → INITIAL;
9. falla durante ejecución → pending → REASSIGNMENT;
10. falla sin alternativa → cancelación clínica;
11. pérdida de capacidad futura gobernada por Appointments;
12. Execution cruza `slot.endsAt` sin consumir el siguiente slot;
13. cleaning desde `releasedAt` real;
14. carrera por último cupo;
15. assign vs disable;
16. recovery Appointments;
17. recovery Wash Administration;
18. recovery Authorization.

Nota: el execution plan agrupa recovery como escenario 15; se recomienda separarlo en tres tests para diagnóstico.

También conservar los adicionales de la primera revisión:

```text
arrival +9m / supervisor decision +12m
same-version WashEntryAuthorized + WashResourceAssigned + WashExecutionStarted
PENDING_REASSIGNMENT con Appointment SCHEDULED + entryAuthorizedAt
redelivery/restart con Outbox/Inbox persistidos
reserva preventiva IMMUNO simétrica
```

---

# 7. P1 — fixes actuales que sí deben conservarse

## 7.1 `calendarId` en Appointments

La solución es coherente con `calendar-context-resolution.md`:

```text
AppointmentTimeSlot
→ calendarId obligatorio

sourceScheduleId
→ service_schedule_projection.calendarId

Query snapshots
→ timeSlot.calendarId

Event incremental
→ calendarId
```

La migración debe seguir siendo probada desde:

```text
DB vacía
DB existente con slots + service_schedule_projection válida
```

## 7.2 `AppointmentScheduled` y Events de transición

Conservar el enriquecimiento de payload necesario para Execution y los nombres:

```text
AppointmentScheduled
AppointmentCancelled
AppointmentMissed
AppointmentTimeSlotCancelled
```

No volver a `slotId` como alias wire.

## 7.3 `ProjectionEventProcessor.Outcome`

El rename de la factory estática:

```text
Outcome.applied()
→ Outcome.success()
```

es correcto porque el record ya genera el accessor de instancia `applied()`.

Conservarlo.

## 7.4 JDBC temporal en Wash Execution

La normalización de `Instant` a tipos JDBC explícitos mediante `JdbcTemporal` es un fix técnico razonable para PostgreSQL/Spring JDBC.

Conservarlo si `clean verify` demuestra que elimina los fallos de binding temporal.

Añadir al menos una prueba de round-trip UTC/timestamptz para evitar regresión por timezone.

---

# 8. P1 — reducir ruido no justificado

Varias clases de Application de Appointments pasan de:

```java
public final class ...
```

a:

```java
public class ...
```

sin otro cambio funcional relacionado.

Si esos cambios fueron necesarios por un fallo concreto de proxy/test, documentar el fallo y conservarlos.

Si no existe un test/fallo que lo justifique, revertirlos para reducir el scope del PR4.

PR4 debe contener únicamente fixes demostrados por integración, no refactors incidentales.

---

# 9. P2 — drift documental canónico menor

`wash-execution-service/calendar-context-resolution.md` establece explícitamente que Appointments debe incluir `calendarId` en los Query snapshots y que esa resolución prevalece sobre listas anteriores.

Sin embargo, `appointments-service/queries.md` todavía muestra una lista de `timeSlot` que omite `calendarId`.

No bloquea la implementación porque la resolución específica prevalece, pero conviene sincronizar esa lista documental posteriormente para eliminar ambigüedad futura.

No revertir la implementación de `calendarId` por esta omisión documental.

---

# 10. Estado de GitHub Actions

El run observado para el head revisado:

```text
head = 941301a31361d07dc7f24ad1b7fc48223b2aa56a
workflow = wash-execution-service
conclusion = failure
```

terminó prácticamente de inmediato y no constituye evidencia de un `clean verify` verde.

Además, actualmente el PR se ejecuta contra la base obsoleta de PR3; retarget a `develop` antes de interpretar el siguiente run.

No mergear PR4 hasta ejecutar el gate manual final en un entorno Docker funcional.

---

# 11. Definition of Ready para la tercera revisión

Pedir nueva revisión únicamente cuando se cumpla:

```text
[ ] PR #41 base = develop
[ ] ahead_by = 1 (o commits PR4 intencionales) / behind_by = 0
[ ] routing Events Appointments = <context>.<subject>.<event>
[ ] contract tests verifican routing exacto + payloads
[ ] calendarId persiste / Event / Query
[ ] harness levanta mínimo 3 servicios + 3 DB + RabbitMQ
[ ] los escenarios PR4 usan RabbitMQ y owners reales
[ ] no se siembran proyecciones como sustituto del E2E principal
[ ] 15 escenarios del execution plan cubiertos
[ ] escenarios adicionales P1 cubiertos
[ ] ArchUnit sigue verde
[ ] migrations desde cero y upgrade verde
[ ] ./mvnw -B clean verify verde en wash-execution-service
[ ] suites de appointments-service y wash-administration-service verdes
```

Cuando todo lo anterior esté listo, realizar la revisión final y **antes del merge** ejecutar la verificación manual integral acordada.
