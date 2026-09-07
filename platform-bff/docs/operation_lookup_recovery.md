# Operation lookup and uncertain outcomes

`GET /api/v1/operations/{operationId}` returns `200 OperationResponse` only when
the operation is visible to the current actor. An unknown operation and an
operation belonging to another actor both return **404 with an empty body**;
frontend must not require a Problem payload for this response.

The route supports anonymous operations. Without a bearer token, account-owned
operations are hidden as 404; an invalid or revoked bearer token produces 401
Problem. An invalid UUID produces 400 Problem. Restore the original actor/session
context before retrying the same saved pollPath. A 404 is not FAILED or EXPIRED,
and does not prove that the command was never accepted or had no effects.

OpenAPI declares optional authentication as `security: [{bearerAuth: []}, {}]`.
The Postman catalogue defaults to `{{bearerToken}}` for account-owned receipts.
For an anonymous receipt, explicitly select No Auth; do not send a stale or
empty token as if it were a valid account credential.

Preserve the operation ID, idempotency key and original request. Do not generate
a new key or resubmit a command automatically after 404. For Lavado, reconcile
the exact appointment/execution rather than selecting whichever appointment is
currently returned by enrollment or Home. If the receipt remains unavailable
and the original outcome cannot be established, show an indeterminate outcome
requiring operational review. This document introduces no recovery endpoint,
retention guarantee or permission to replay messages.

This describes existing HTTP behavior. The OpenAPI correction only makes its
explicit 404 response override the generic Problem default; it changes no
authorization, operation persistence or domain transition.
