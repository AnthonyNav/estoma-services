package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.port.AuthorizationProjectionEvent;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.consumed.AccountRoleAssignedPayload;
import com.estoma.washadministration.contracts.consumed.AccountRoleChangedPayload;
import com.estoma.washadministration.contracts.consumed.SystemAccessGrantedPayload;
import com.estoma.washadministration.contracts.consumed.SystemAccessRestoredPayload;
import com.estoma.washadministration.contracts.consumed.SystemAccessRevokedPayload;
import com.estoma.washadministration.contracts.consumed.SystemAccessSuspendedPayload;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationEventMapper {

  private final EnvelopeJsonCodec codec;

  public AuthorizationEventMapper(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  public AuthorizationProjectionEvent map(MessageEnvelope envelope) {
    if (envelope.aggregateVersion() == null || envelope.aggregateVersion() <= 0) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_VERSION_REQUIRED");
    }
    int version = envelope.aggregateVersion();
    return switch (envelope.messageType()) {
      case "AccountRoleAssigned" -> {
        AccountRoleAssignedPayload payload = codec.convertPayload(envelope, AccountRoleAssignedPayload.class);
        yield new AuthorizationProjectionEvent.RoleAssigned(
            envelope.messageId(), account(payload.accountId()), text(payload.roleCode()), version,
            timestamp(payload.assignedAt()));
      }
      case "AccountRoleChanged" -> {
        AccountRoleChangedPayload payload = codec.convertPayload(envelope, AccountRoleChangedPayload.class);
        yield new AuthorizationProjectionEvent.RoleChanged(
            envelope.messageId(), account(payload.accountId()), text(payload.previousRoleCode()),
            text(payload.newRoleCode()), version, timestamp(payload.changedAt()));
      }
      case "SystemAccessGranted" -> {
        SystemAccessGrantedPayload payload = codec.convertPayload(envelope, SystemAccessGrantedPayload.class);
        yield new AuthorizationProjectionEvent.SystemAccessGranted(
            envelope.messageId(), account(payload.accountId()), text(payload.systemCode()), version,
            timestamp(payload.grantedAt()));
      }
      case "SystemAccessSuspended" -> {
        SystemAccessSuspendedPayload payload = codec.convertPayload(envelope, SystemAccessSuspendedPayload.class);
        yield new AuthorizationProjectionEvent.SystemAccessSuspended(
            envelope.messageId(), account(payload.accountId()), text(payload.systemCode()), version,
            timestamp(payload.suspendedAt()));
      }
      case "SystemAccessRevoked" -> {
        SystemAccessRevokedPayload payload = codec.convertPayload(envelope, SystemAccessRevokedPayload.class);
        yield new AuthorizationProjectionEvent.SystemAccessRevoked(
            envelope.messageId(), account(payload.accountId()), text(payload.systemCode()), version,
            timestamp(payload.revokedAt()));
      }
      case "SystemAccessRestored" -> {
        SystemAccessRestoredPayload payload = codec.convertPayload(envelope, SystemAccessRestoredPayload.class);
        yield new AuthorizationProjectionEvent.SystemAccessRestored(
            envelope.messageId(), account(payload.accountId()), text(payload.systemCode()), version,
            timestamp(payload.restoredAt()));
      }
      default -> throw new InvalidMessageException("AUTHORIZATION_EVENT_TYPE_NOT_ALLOWED");
    };
  }

  private static UUID account(UUID accountId) {
    if (accountId == null) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_ACCOUNT_REQUIRED");
    }
    return accountId;
  }

  private static String text(String value) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_FIELD_REQUIRED");
    }
    return value;
  }

  private static Instant timestamp(Instant value) {
    if (value == null) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_TIMESTAMP_REQUIRED");
    }
    return value;
  }
}
