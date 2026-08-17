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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationEventMapper {

  private static final Set<String> REASONS = Set.of("ADMINISTRATIVE", "ROLE_CHANGED", "SECURITY", "OTHER");
  private final EnvelopeJsonCodec codec;

  public AuthorizationEventMapper(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  public AuthorizationProjectionEvent map(MessageEnvelope envelope) {
    if (envelope.aggregateId() == null
        || envelope.aggregateVersion() == null
        || envelope.aggregateVersion() <= 0) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_AGGREGATE_REQUIRED");
    }
    int version = envelope.aggregateVersion();
    return switch (envelope.messageType()) {
      case "AccountRoleAssigned" -> {
        AccountRoleAssignedPayload payload = codec.convertPayload(envelope, AccountRoleAssignedPayload.class);
        requireSame(envelope.aggregateId(), account(payload.accountId()), "AUTHORIZATION_ROLE_AGGREGATE_MISMATCH");
        require(payload.roleId(), "AUTHORIZATION_ROLE_ID_REQUIRED");
        yield new AuthorizationProjectionEvent.RoleAssigned(
            envelope.messageId(), payload.accountId(), text(payload.roleCode()), version,
            timestamp(payload.assignedAt()));
      }
      case "AccountRoleChanged" -> {
        AccountRoleChangedPayload payload = codec.convertPayload(envelope, AccountRoleChangedPayload.class);
        requireSame(envelope.aggregateId(), account(payload.accountId()), "AUTHORIZATION_ROLE_AGGREGATE_MISMATCH");
        require(payload.previousRoleId(), "AUTHORIZATION_PREVIOUS_ROLE_ID_REQUIRED");
        require(payload.newRoleId(), "AUTHORIZATION_NEW_ROLE_ID_REQUIRED");
        yield new AuthorizationProjectionEvent.RoleChanged(
            envelope.messageId(), payload.accountId(), text(payload.previousRoleCode()),
            text(payload.newRoleCode()), version, timestamp(payload.changedAt()));
      }
      case "SystemAccessGranted" -> {
        SystemAccessGrantedPayload payload = codec.convertPayload(envelope, SystemAccessGrantedPayload.class);
        validateAccessIdentity(envelope, payload.systemAccessId(), payload.accountId(), payload.systemId(), payload.systemCode());
        require(payload.roleId(), "AUTHORIZATION_ROLE_ID_REQUIRED");
        text(payload.roleCode());
        requireStatus(payload.status(), "ACTIVE");
        yield new AuthorizationProjectionEvent.SystemAccessGranted(
            envelope.messageId(), payload.accountId(), payload.systemCode(), version,
            timestamp(payload.grantedAt()));
      }
      case "SystemAccessSuspended" -> {
        SystemAccessSuspendedPayload payload = codec.convertPayload(envelope, SystemAccessSuspendedPayload.class);
        validateAccessIdentity(envelope, payload.systemAccessId(), payload.accountId(), payload.systemId(), payload.systemCode());
        requireStatus(payload.status(), "SUSPENDED");
        requireStatus(payload.previousStatus(), "ACTIVE");
        validateReason(payload.reasonCode(), payload.reasonDetail());
        yield new AuthorizationProjectionEvent.SystemAccessSuspended(
            envelope.messageId(), payload.accountId(), payload.systemCode(), version,
            timestamp(payload.suspendedAt()));
      }
      case "SystemAccessRevoked" -> {
        SystemAccessRevokedPayload payload = codec.convertPayload(envelope, SystemAccessRevokedPayload.class);
        validateAccessIdentity(envelope, payload.systemAccessId(), payload.accountId(), payload.systemId(), payload.systemCode());
        requireStatus(payload.status(), "REVOKED");
        if (!Set.of("ACTIVE", "SUSPENDED").contains(text(payload.previousStatus()))) {
          throw new InvalidMessageException("AUTHORIZATION_PREVIOUS_STATUS_INVALID");
        }
        validateReason(payload.reasonCode(), payload.reasonDetail());
        yield new AuthorizationProjectionEvent.SystemAccessRevoked(
            envelope.messageId(), payload.accountId(), payload.systemCode(), version,
            timestamp(payload.revokedAt()));
      }
      case "SystemAccessRestored" -> {
        SystemAccessRestoredPayload payload = codec.convertPayload(envelope, SystemAccessRestoredPayload.class);
        validateAccessIdentity(envelope, payload.systemAccessId(), payload.accountId(), payload.systemId(), payload.systemCode());
        requireStatus(payload.status(), "ACTIVE");
        if (!Set.of("SUSPENDED", "REVOKED").contains(text(payload.previousStatus()))) {
          throw new InvalidMessageException("AUTHORIZATION_PREVIOUS_STATUS_INVALID");
        }
        yield new AuthorizationProjectionEvent.SystemAccessRestored(
            envelope.messageId(), payload.accountId(), payload.systemCode(), version,
            timestamp(payload.restoredAt()));
      }
      default -> throw new InvalidMessageException("AUTHORIZATION_EVENT_TYPE_NOT_ALLOWED");
    };
  }

  private static void validateAccessIdentity(
      MessageEnvelope envelope,
      UUID systemAccessId,
      UUID accountId,
      UUID systemId,
      String systemCode) {
    require(systemAccessId, "AUTHORIZATION_SYSTEM_ACCESS_ID_REQUIRED");
    requireSame(envelope.aggregateId(), systemAccessId, "AUTHORIZATION_ACCESS_AGGREGATE_MISMATCH");
    account(accountId);
    require(systemId, "AUTHORIZATION_SYSTEM_ID_REQUIRED");
    text(systemCode);
  }

  private static void validateReason(String reasonCode, String reasonDetail) {
    String code = text(reasonCode);
    if (!REASONS.contains(code)) {
      throw new InvalidMessageException("AUTHORIZATION_REASON_CODE_INVALID");
    }
    if ("OTHER".equals(code) && (reasonDetail == null || reasonDetail.isBlank())) {
      throw new InvalidMessageException("AUTHORIZATION_REASON_DETAIL_REQUIRED");
    }
  }

  private static void requireStatus(String actual, String expected) {
    if (!expected.equals(text(actual))) {
      throw new InvalidMessageException("AUTHORIZATION_STATUS_INVALID");
    }
  }

  private static UUID account(UUID accountId) {
    return require(accountId, "AUTHORIZATION_EVENT_ACCOUNT_REQUIRED");
  }

  private static String text(String value) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_FIELD_REQUIRED");
    }
    return value;
  }

  private static Instant timestamp(Instant value) {
    return require(value, "AUTHORIZATION_EVENT_TIMESTAMP_REQUIRED");
  }

  private static <T> T require(T value, String code) {
    if (value == null) {
      throw new InvalidMessageException(code);
    }
    return value;
  }

  private static void requireSame(UUID expected, UUID actual, String code) {
    if (!expected.equals(actual)) {
      throw new InvalidMessageException(code);
    }
  }
}
