# Wash Execution — PR4 Review Corrections

**Estado:** gate de corrección previo a PR4
**Base revisada:** `develop` después de PR3 (`67c7856caa4316df75f54e6ad886bc5fbcda06bf`)
**PR auditado:** `#41 test(wash-execution): PR4 integration hardening`

## 1. Conclusión de la revisión

El PR4 actual **no debe fusionarse ni corregirse incrementalmente sobre su commit original**.

Su commit original fue construido sobre una versión anterior de PR1–PR3 y hoy reintroduce piezas que ya fueron reemplazadas durante la revisión:

- Spring/`@Transactional` dentro de Application;
- Consumer Inbox dentro de `WashExecutionCommandService`;
- APIs antiguas de capacidad como `countActiveByTankAndSlotForUpdate`;
- listeners, recovery y Outbox Relay anteriores a PR3;
- wiring y DTOs incompatibles con la base revisada;
- un `WashExecutionQueryService` acoplado a Spring y con semánticas que no corresponden al contrato V1 vigente.

El delta original de PR4 era de solo 15 archivos, pero al compararlo con el PR3 revisado termina arrastrando decenas de archivos del stack anterior. La estrategia correcta es:

```text
current develop
→ nueva base de PR4
→ conservar PR1 + PR2 + PR3 sin modificarlos
→ añadir exclusivamente harness/tests E2E y defect fixes demostrados por esos E2E
```

No hacer cherry-pick del commit original de PR4 `d84c21cab92fc88f1ebb9505b5125cfa350c246f`.

---

# 2. P0 — reconstruir la rama PR4

Antes de implementar escenarios:

1. llevar la rama de PR4 al `develop` actual que ya contiene PR3;
2. eliminar todo el delta heredado del PR4 viejo;
3. reconstruir PR4 como **integración E2E del bounded context**, no como otro PR de implementación interna de `wash-execution-service`;
4. el diff esperado debe ser principalmente:
   - tests/harness E2E;
   - fixtures/configuración de test;
   - documentación de ejecución;
   - correcciones de bugs concretos descubiertos por los E2E, si aparecen.

Gate útil antes de trabajar:

```bash
git diff --name-only develop...HEAD
```

No debe volver a mostrar el dominio, repositories, listeners y wiring completo de PR1–PR3 como si fueran cambios de PR4.

---

# 3. P0 — blocker cross-service: Appointments todavía no entrega el contexto que Execution necesita

PR4 no puede demostrar el flujo real mientras el productor `appointments-service` no publique el contrato que `wash-execution-service` consume.

La documentación canónica ya cerró que `AppointmentTimeSlot` debe transportar `calendarId`. Execution PR3 lo exige y falla cerrado si falta.

## 3.1 Query snapshots de Appointments

En la implementación actual de Appointments PR3, `AppointmentExecutionQueryService.slotNode(...)` devuelve:

```text
appointmentTimeSlotId
serviceDate
startsAt
endsAt
timezone
arrivalToleranceUntil
status
version
```

Falta:

```text
calendarId
```

Corregir ambos contratos owner-side:

```text
GetAppointmentExecutionContext
timeSlot.calendarId

ListAppointmentExecutionContextsPage
contexts[].timeSlot.calendarId
```

`calendarId` debe derivarse del owner mediante la relación administrativa que originó el slot; nunca por fecha, por el calendario ACTIVE actual ni por solapamiento temporal.

## 3.2 `AppointmentTimeSlotCreated`

La implementación actual de Appointments publica esencialmente:

```json
{"slotId":"..."}
```

Eso no alcanza para construir la proyección incremental de Execution.

El Event que llegue a Execution debe contener, como mínimo:

```text
appointmentTimeSlotId
calendarId
serviceDate
startsAt
endsAt
timezone
arrivalToleranceUntil
status
```

Puede conservar además los campos propios ya canónicos de Appointments, por ejemplo:

```text
sourceScheduleId
bookingDeadlineAt
cancellationDeadlineAt
```

La clave es que el consumidor pueda crear/reconciliar `appointment_time_slot_projection` sin Query síncrona en el flujo funcional.

Usar el nombre wire canónico:

```text
appointmentTimeSlotId
```

No usar `slotId` como alias alterno entre servicios.

## 3.3 `AppointmentScheduled`

La implementación actual de Appointments publica solo una forma reducida similar a:

```text
appointmentId
slotId
status
```

Execution necesita como mínimo:

```text
appointmentId
studentAccountId
appointmentTimeSlotId
courseSectionId
appointmentType
instrumentCount
pieceType
status
```

El Event debe poder mantener `appointment_projection` sin leer directamente `appointments-db` ni llamar a Appointments por HTTP.

## 3.4 Eventos de transición

Verificar y normalizar también los identificadores de:

```text
AppointmentCancelled
AppointmentMissed
AppointmentTimeSlotCancelled
```

Execution espera las claves propietarias canónicas:

```text
appointmentId
appointmentTimeSlotId
```

La ausencia causal de la proyección puede resolverse mediante `DEFERRED + recovery`, pero no debe usarse esa capacidad para justificar Events permanentemente incompletos.

## 3.5 Tests del productor

Antes del E2E, Appointments debe tener contract tests que demuestren al menos:

```text
AppointmentTimeSlotCreated incluye calendarId + contexto temporal
AppointmentScheduled incluye contexto operativo completo
GetAppointmentExecutionContext incluye timeSlot.calendarId
ListAppointmentExecutionContextsPage incluye timeSlot.calendarId
```

Hasta que esto esté corregido, Execution correctamente permanecerá fail-closed/DEGRADED durante recovery de Appointments y PR4 no puede considerarse válido.

---

# 4. P0 — PR4 debe ser E2E real del bounded context

El objetivo contractual de PR4 es validar conjuntamente, como mínimo:

```text
appointments-service
wash-administration-service
wash-execution-service
RabbitMQ
PostgreSQL separado por servicio
```

Para Authorization, preferir también el servicio real ya que Execution valida actores mediante su proyección local y PR3 implementa recovery. Si el harness no levanta Authorization completo, cualquier fixture sustituto debe respetar exactamente su contrato RabbitMQ; no reemplazar la integración escribiendo directamente filas de autorización para todos los escenarios.

Opciones aceptables de harness:

```text
Testcontainers
Docker Compose de test
containers/procesos reales coordinados desde tests
```

Lo importante es que el camino de negocio sea:

```text
Command RabbitMQ
→ owner real
→ DB propia
→ Transactional Outbox
→ RabbitMQ
→ consumidor real
→ Consumer Inbox/proyección
```

No validar PR4 llamando directamente handlers/repositories como sustituto del flujo distribuido.

No compartir una base de datos entre servicios.

---

# 5. P0 — escenarios obligatorios del execution plan

PR4 debe cubrir los 15 escenarios del `execution_plan.md`.

## 5.1 Flujo NORMAL completo

```text
ScheduleAppointment
→ AppointmentScheduled
→ RegisterWashArrival
→ DecideWashEntry AUTHORIZED
→ ResourceAssignment INITIAL
→ IN_PROGRESS
→ SubmitWashExit
→ CompleteWashExecution
→ Appointment COMPLETED
```

Validar Result + Events + estado final en ambos owners.

## 5.2 JOURNEY preferred/fallback

Probar por separado:

```text
JOURNEY → cabina JOURNEY preferida
JOURNEY → NORMAL fallback cuando JOURNEY no es utilizable
```

La selección debe seguir siendo determinista.

## 5.3 IMMUNOCOMPROMISED + reserva preventiva + limpieza real

Validar:

```text
cita IMMUNO
→ reserva preventiva en Appointments
→ uso físico exclusivo
→ release real
→ CleaningPeriodStarted
→ completedAt = expectedEndsAt
→ CleaningPeriodCompleted
→ Appointments reconcilia capacidad
```

## 5.4 Cancelar IMMUNO sin uso

```text
Appointment IMMUNO futura/activa
→ CANCELLED sin uso físico
→ desaparece reserva preventiva
→ NO CleaningPeriod físico
```

## 5.5 MISSED IMMUNO sin uso

Mismo criterio:

```text
MISSED
→ liberar reserva preventiva
→ no CleaningPeriod
```

## 5.6 ENTRY_REJECTED IMMUNO sin uso

```text
WashEntryRejected
→ Appointment ENTRY_REJECTED
→ liberar reserva preventiva
→ no CleaningPeriod
```

## 5.7 Autorizada sin recurso

```text
WashEntryAuthorized
→ sin candidato
→ Execution PENDING_REASSIGNMENT
→ Appointment permanece SCHEDULED
→ entryAuthorizedAt != null
→ ResolvePendingReassignment
→ ResourceAssignment INITIAL
→ IN_PROGRESS
```

## 5.8 Falla durante ejecución + reasignación

```text
IN_PROGRESS
→ DisableOperationalResource
→ PENDING_REASSIGNMENT
→ conservar assignment anterior hasta resolución
→ ResolvePendingReassignment
→ release anterior
→ ResourceAssignment REASSIGNMENT
→ IN_PROGRESS
```

## 5.9 Falla sin alternativa

```text
IN_PROGRESS
→ PENDING_REASSIGNMENT
→ sin alternativa válida
→ CancelWashExecutionByClinic
→ Execution CANCELLED
→ Appointment CANCELLED / CLINIC
→ sin penalización al alumno
```

## 5.10 Pérdida de capacidad de citas futuras

Un cambio operativo/configuracional debe llegar a Appointments y recalcular capacidad futura.

Validar que cualquier cancelación resultante pertenece a Appointments para las citas `SCHEDULED`; Execution no debe cancelar automáticamente futuras citas.

## 5.11 Ejecución que cruza `slot.endsAt`

```text
Execution del slot A sigue abierta
→ inicia slot B
→ no restar por ese solo hecho capacidad lógica a B
```

La capacidad sigue acotada por:

```text
Tank + AppointmentTimeSlot
```

## 5.12 Cleaning real distinto del plan

Ejemplo obligatorio:

```text
slot previsto: 10:00-11:00
releasedAt real: 10:35
CleaningPeriod: 10:35-13:35
```

No usar `slot.endsAt` para iniciar la limpieza física.

## 5.13 Carrera por último cupo

Emitir dos intenciones realmente concurrentes hacia el mismo `Tank + slot` con un solo cupo restante.

Resultado obligatorio:

```text
máximo una nueva assignment
sin overbooking
otra intención queda sin candidato/rechazada según flujo
```

La prueba debe atravesar el mismo RabbitMQ/use case que producción, no solo ejecutar dos repository methods.

## 5.14 Assign vs disable

Ejecutar concurrentemente:

```text
asignación de recurso
DisableOperationalResource del mismo recurso
```

El estado final debe corresponder a un orden serializable válido; nunca:

```text
assignment aparentemente utilizable
+
indisponibilidad ignorada
```

## 5.15 Recovery de cada proyección

Cubrir por separado:

```text
Appointments
Wash Administration
Authorization
```

Validar:

```text
REBUILDING/DEGRADED → readiness DOWN
snapshot autoritativo
+ Events concurrentes
→ monotonic convergence
→ DEFERRED drenados
→ READY
```

No tratar RabbitMQ como Event Store.

---

# 6. P1 — escenarios adicionales descubiertos durante PR1–PR3

Estos casos deben agregarse porque protegen bugs concretos ya identificados.

## 6.1 Llegada dentro de tolerancia y decisión posterior

```text
slot.startsAt + 9m
→ WashArrivalRegistered
supervisor decide en +12m
→ Appointment NO puede pasar a MISSED
```

La evidencia de llegada válida debe proteger contra un `MarkAppointmentAsMissed` irreversible tardío.

## 6.2 Same-version Events

Una misma transacción puede publicar:

```text
WashEntryAuthorized
WashResourceAssigned
WashExecutionStarted
```

con la misma `executionVersion`.

Appointments debe aplicar cada hecho exactamente una vez por `messageId/sourceEventId`; no deduplicar exclusivamente por `executionVersion`.

Verificar que:

```text
entryAuthorizedAt se conserva
Appointment termina IN_PROGRESS
ningún Event válido se descarta por compartir version
```

## 6.3 Entry authorized sin recurso

Además del escenario 5.7, comprobar explícitamente:

```text
Execution = PENDING_REASSIGNMENT
Appointment = SCHEDULED
Appointment.entryAuthorizedAt != null
```

No introducir estado `ENTRY_AUTHORIZED`.

## 6.4 Duplicates/restart

Al menos un bounded-flow debe incluir:

```text
mensaje duplicado/redelivery
restart de Execution con Outbox/Inbox persistido
→ no repetir mutación
→ no perder Result/Event pendiente
```

PR3 ya cubre estas piezas de forma aislada; PR4 debe demostrar que no se rompen al integrarlas con los demás servicios.

---

# 7. P1 — reserva preventiva IMMUNO simétrica

Para una cita existente 14:00–15:00, validar expresamente:

```text
11:00–12:00 → conflicto
12:00–13:00 → conflicto
13:00–14:00 → conflicto
14:00–15:00 → reservada
15:00–16:00 → conflicto
16:00–17:00 → conflicto
17:00–18:00 → conflicto
18:00–19:00 → potencialmente válida
```

No validar únicamente slots posteriores; el conflicto es simétrico porque la limpieza prevista de una cita anterior también puede invadir la cita existente.

---

# 8. P1 — Query/read-model scope

No rescatar el viejo `WashExecutionQueryService` del PR4.

Problemas del diseño anterior:

- `@Service` dentro de Application;
- lectura de occupancy usando lock de escritura/capacidad;
- hardcode de `AppointmentType.NORMAL`;
- cleaning range artificial de años;
- pending ordenado por `arrivedAt` en lugar de evidencia de autorización;
- mezcla de Query/read-model para UI con la superficie service-to-service.

Contrato vigente:

```text
GetWashExecutionIntegrationContext
ListWashExecutionIntegrationContextsPage
```

son Queries autoritativas de recovery para Appointments.

Las pantallas/supervisión normal se sirven mediante read models del BFF alimentados por Events.

No agregar nuevas Queries públicas de supervisión a Execution en PR4 sin una modificación canónica previa.

---

# 9. Cambios del PR4 viejo que NO deben reaparecer

No introducir nuevamente:

```text
ProcessedMessageRepository dentro de WashExecutionCommandService
@Transaction/@Autowired/@Service dentro de Application
application/recovery/ProjectionRecoveryCoordinator viejo
CleaningPeriodCompletionJob dentro de Application
EventProjectionListener viejo
RecoveryResultListener viejo
RecoveryQueryPublisher viejo
OutboxRelay viejo
countActiveByTankAndSlotForUpdate
Inbox registerIfAbsent antiguo
authorization opcional/null
CompleteCleaningPeriod manual
HTTP interno de negocio
```

No relajar ArchUnit para hacer compilar PR4.

---

# 10. Qué puede rescatarse conceptualmente del PR4 anterior

Muy poco código debe reutilizarse literalmente.

Se pueden conservar como **intenciones de test**, reescritas contra la base actual:

- comprobar `logicalComponentId = wash-execution` y nombres de queues;
- aumentar cobertura de comportamiento de Commands si aparece un hueco real;
- probar ownership del alumno en `SubmitWashExit`;
- probar capacidad bajo concurrencia.

Pero PR1–PR3 ya tienen pruebas mucho más fuertes de esos puntos. PR4 debe aportar principalmente el valor que aún falta: interacción entre servicios reales.

---

# 11. Estructura recomendada del PR4 nuevo

Ejemplo conceptual:

```text
wash-execution-service/
└── src/test/...
    └── e2e/
        ├── BoundedContextHarness
        ├── NormalWashFlowE2EIT
        ├── JourneyAllocationE2EIT
        ├── ImmunocompromisedFlowE2EIT
        ├── FailureAndReassignmentE2EIT
        ├── CapacityConcurrencyE2EIT
        └── ProjectionRecoveryE2EIT
```

El nombre/ubicación exacta puede variar. El requisito es que el harness levante los componentes reales necesarios y que los tests se ejecuten de forma reproducible.

Preferir polling con deadline sobre estados/Results durables.

Evitar usar `Thread.sleep(...)` como criterio de correctitud distribuida.

---

# 12. Gate final antes del merge de PR4

Debido a que GitHub Actions está actualmente sin ejecutar jobs por la cuota agotada de la cuenta, la verificación final debe realizarse en un entorno con Docker antes de fusionar PR4.

Como mínimo:

```bash
cd wash-execution-service
./mvnw -B clean verify
```

Además, ejecutar el comando exacto del harness E2E si no está integrado en `mvn verify`.

Como sanity check de los owners modificados para habilitar PR4:

```bash
cd appointments-service
./mvnw -B clean verify

cd ../wash-administration-service
./mvnw -B clean verify

cd ../authorization-service
./mvnw -B clean verify
```

Y comprobar:

```text
[ ] ArchUnit verde
[ ] Flyway desde DB vacía
[ ] Postgres separado por servicio
[ ] RabbitMQ real
[ ] los 15 escenarios del execution plan
[ ] escenarios same-version/arrival evidence
[ ] no overbooking bajo carrera
[ ] no CleaningPeriod sin uso físico
[ ] CleaningPeriod termina en releasedAt + 3h
[ ] Appointments transporta calendarId incremental + recovery
[ ] Appointments transporta AppointmentScheduled completo
[ ] recovery Appointments/Admin/Authorization converge
[ ] readiness DOWN durante rebuild/deferred y UP después
[ ] duplicados/restart no repiten efectos
[ ] ningún servicio lee/escribe directamente la DB de otro
[ ] ningún flujo de negocio usa HTTP/gRPC directo
[ ] diff de PR4 no reintroduce PR1–PR3 antiguos
```

Solo después de este gate debe considerarse PR4 mergeable.

---

# 13. Orden recomendado de trabajo

```text
1. Corregir Appointments PR3 wire/recovery (`calendarId` + payloads completos).
2. Fusionar/sincronizar Appointments corregido.
3. Reset/recrear PR4 desde `develop` actual.
4. Construir harness real del bounded context.
5. Implementar escenarios 1–15.
6. Agregar arrival-evidence + same-version + restart/duplicate.
7. Corregir únicamente bugs demostrados por E2E.
8. Ejecutar clean verify + E2E con Docker.
9. Revisar diff final.
10. Recién entonces solicitar merge de PR4.
```

## Definition of Done revisada

PR4 queda terminado cuando no solo `wash-execution-service` funciona aislado, sino cuando puede demostrarse que:

```text
Appointments
+ Wash Administration
+ Wash Execution
+ Authorization necesario
+ RabbitMQ
+ PostgreSQL separados
```

convergen bajo los flujos V1 normales, fallos, concurrencia y recovery definidos por los contratos canónicos.
