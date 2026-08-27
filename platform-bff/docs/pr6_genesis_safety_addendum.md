# Platform BFF — PR6 Genesis Safety Addendum

**Estado:** corrección normativa de seguridad para `pr6_deployment_readiness_gate.md` §3.1  
**Precedencia:** este addendum prevalece sobre cualquier lectura más permisiva de la sección de genesis baseline.

## Regla de elegibilidad

La genesis baseline **solo** es válida para un ambiente realmente nuevo en el que el BFF y sus bindings estén activos antes de que los owners produzcan estado/eventos que deban existir en las proyecciones del BFF.

No basta con que `bff-db` esté vacío.

Antes de promover proyecciones no recuperables a `READY`, PR6 debe exigir una condición operacional explícita que garantice que no existe historia owner previa que el BFF esté omitiendo.

Se considera elegible únicamente cuando, para ese ambiente:

```text
BFF DB/schema creado
+ queues/bindings del BFF creados
+ genesis marker ausente
+ projection_status en generación inicial BOOTSTRAPPING
+ owners aún no han aceptado tráfico/mutaciones que produzcan estado proyectable
→ genesis baseline permitida
```

## Ambientes con historia previa

Si cualquiera de los owners ya contiene estado histórico relevante —por ejemplo Persons, Accounts/sesiones/revocaciones, Academic, Communications, Wash Administration, Appointments o Wash Execution— una proyección vacía **no puede** declararse confiable mediante genesis.

En ese caso PR6 debe fallar cerrado y exigir una de estas rutas:

1. restaurar `bff-db` desde backup/PITR compatible con ese ambiente; o
2. agregar primero un contrato owner explícito de snapshot/recovery y después implementar su recovery en BFF.

Queda prohibido:

- inferir que un owner está vacío únicamente porque el BFF no recibió Events;
- marcar `READY` por timeout, por primer Event recibido o por ausencia de filas locales;
- usar genesis para reconstruir un ambiente existente después de pérdida de `bff-db`;
- usar genesis como sustituto de recovery owner-driven;
- aceptar tokens/sesiones previas sin una proyección de revocación confiable.

## Revocation

`revocation` requiere la misma regla estricta. Una baseline vacía solo es segura antes de que existan sesiones/revocaciones históricas en el ambiente. Si Authentication ya operó previamente, el BFF no puede asumir `revocation = READY` desde cero.

## Responsabilidad de despliegue

`estoma-infra` deberá garantizar el orden de bootstrap inicial:

```text
PostgreSQL BFF
→ RabbitMQ topology/bindings BFF
→ BFF inicia y registra genesis segura
→ BFF queda listo para consumir Events
→ owners reciben tráfico funcional
```

El flag de genesis debe ser one-shot, estar desactivado por defecto y retirarse/deshabilitarse después del primer bootstrap exitoso.

## Gate adicional de PR6

Además del Definition of Done original, PR6 debe demostrar con pruebas que:

- genesis funciona en un ambiente vacío y nuevo;
- restart posterior no repite genesis;
- estado local incompatible rechaza genesis;
- un ambiente marcado como preexistente/histórico rechaza genesis aunque `bff-db` esté vacío;
- pérdida simulada de `bff-db` no produce auto-promoción a `READY`;
- revocation permanece fail-closed si no puede demostrarse una baseline segura.
