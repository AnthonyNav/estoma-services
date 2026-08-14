package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport-neutral Authorization facts used to maintain the local projection.
 *
 * <p>PR3 maps the external Authorization Event contract into these explicit models.
 */
public sealed interface AuthorizationProjectionEvent
    permits AuthorizationProjectionEvent.RoleAssigned,
        AuthorizationProjectionEvent.RoleChanged,
        AuthorizationProjectionEvent.SystemAccessEvent {

  UUID messageId();

  UUID accountId();

  int sourceVersion();

  Instant occurredAt();

  record RoleAssigned(
      UUID messageId,
      UUID accountId,
      String roleCode,
      int sourceVersion,
      Instant occurredAt)
      implements AuthorizationProjectionEvent {}

  record RoleChanged(
      UUID messageId,
      UUID accountId,
      String previousRoleCode,
      String newRoleCode,
      int sourceVersion,
      Instant occurredAt)
      implements AuthorizationProjectionEvent {}

  sealed interface SystemAccessEvent extends AuthorizationProjectionEvent
      permits SystemAccessGranted,
          SystemAccessSuspended,
          SystemAccessRevoked,
          SystemAccessRestored {

    String systemCode();
  }

  record SystemAccessGranted(
      UUID messageId,
      UUID accountId,
      String systemCode,
      int sourceVersion,
      Instant occurredAt)
      implements SystemAccessEvent {}

  record SystemAccessSuspended(
      UUID messageId,
      UUID accountId,
      String systemCode,
      int sourceVersion,
      Instant occurredAt)
      implements SystemAccessEvent {}

  record SystemAccessRevoked(
      UUID messageId,
      UUID accountId,
      String systemCode,
      int sourceVersion,
      Instant occurredAt)
      implements SystemAccessEvent {}

  record SystemAccessRestored(
      UUID messageId,
      UUID accountId,
      String systemCode,
      int sourceVersion,
      Instant occurredAt)
      implements SystemAccessEvent {}
}
