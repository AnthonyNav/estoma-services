# Platform BFF — PR2 Owner Contract Addendum

**Estado:** BLOCKER contractual complementario de `pr2_correction_gate.md`  
**Aplica a:** `platform-bff/feat/pr2-durable-command-gateway`

## Hallazgo

El contrato canónico vigente de `appointments-service` define:

```text
CancelAppointmentTimeSlot
actor/origen = Administrador de Lavado mediante BFF
exposición   = Comando durable iniciado por cliente
resultado    = 202 Accepted + operationId + Result correlacionado
```

Sin embargo, existe drift entre artefactos locales:

```text
appointments-service/docs/system_contract.md
→ clasifica CancelAppointmentTimeSlot entre operaciones internas

platform-bff/docs/system_contract.md
→ no lo incluye en el catálogo BFF-initiable heredado de PR0

PR2 CommandRegistry
→ no allowlistea CancelAppointmentTimeSlot
```

La precedencia contractual definida para el BFF establece que el contrato owner prevalece sobre el catálogo derivado del BFF cuando existe contradicción.

## Corrección requerida antes de cerrar PR2

1. Tratar `CancelAppointmentTimeSlot` como **BFF-initiable** mientras el contrato canónico de Appointments mantenga esa exposición.
2. Corregir el contrato local de `appointments-service` para dejar de clasificarlo como exclusivamente interno.
3. Corregir el contrato local de `platform-bff` para reflejar la capacidad owner.
4. Añadir el Command al allowlist/registry de PR2 sólo después de verificar su wire real en el owner implementado.
5. Verificar explícitamente:

```text
messageType
messageVersion
producer esperado del Result
Result messageType/version reales
expectedVersion / aggregate identity requerida
```

6. No inferir Result names con una convención genérica como:

```text
<Command>Succeeded
<Command>Rejected
<Command>Result
```

si el owner no publica exactamente ese wire.

## Ruta HTTP

Este addendum **no autoriza inventar** un endpoint público.

Mientras la documentación canónica del BFF no cierre una ruta para esta capacidad:

```text
owner capability / Command allowlist = REQUIRED
public HTTP adapter                = GATED_BY_BFF_CANONICAL_INTERFACE
```

Así se evita resolver el drift trasladando una decisión de interfaz pública al código.

## Gate

PR2 no debe considerarse contractual completo si su Command registry pretende representar el catálogo BFF-initiable y todavía omite una capacidad que el owner canónico expone mediante BFF.
