# Canonical idempotency key

Communications follows the platform V1 envelope: `idempotencyKey` is a functional **string**, not a UUID constraint.

The service preserves the exact incoming key through the Communication aggregate and Transactional Outbox. UUID callers remain accepted only at selected in-process compatibility boundaries and are normalized to their textual representation.
