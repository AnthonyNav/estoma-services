package com.estoma.washadministration.application.authorization;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies Authorization facts to the local monotonic projections; transport belongs to PR3. */
@Component
public class AuthorizationProjectionUpdater {

  private static final String CONSUMER = "wash-administration.authorization-events";
  private static final String SYSTEM = "LAVADO_ULTRASONICO";

  private final ProcessedMessageRepository processedMessages;
  private final AuthorizationRoleProjectionRepository roles;
  private final AuthorizationAccessProjectionRepository accesses;

  public AuthorizationProjectionUpdater(
      ProcessedMessageRepository processedMessages,
      AuthorizationRoleProjectionRepository roles,
      AuthorizationAccessProjectionRepository accesses) {
    this.processedMessages = processedMessages;
    this.roles = roles;
    this.accesses = accesses;
  }

  @Transactional
  public boolean apply(AuthorizationProjectionEvent event) {
    if (event.type().startsWith("SystemAccess") && !SYSTEM.equals(event.systemCode())) {
      return false;
    }
    Instant now = event.occurredAt() == null ? Instant.now() : event.occurredAt();
    if (!processedMessages.registerIfAbsent(CONSUMER, event.messageId(), null, now)) {
      return false;
    }
    switch (event.type()) {
      case "AccountRoleAssigned", "AccountRoleChanged" -> roles.upsert(
          event.accountId(), event.roleCode(), event.sourceVersion(), now);
      case "SystemAccessGranted", "SystemAccessRestored" -> accesses.upsert(
          event.accountId(), SYSTEM, "ACTIVE", event.sourceVersion(), now);
      case "SystemAccessSuspended" -> accesses.upsert(
          event.accountId(), SYSTEM, "SUSPENDED", event.sourceVersion(), now);
      case "SystemAccessRevoked" -> accesses.upsert(
          event.accountId(), SYSTEM, "REVOKED", event.sourceVersion(), now);
      default -> throw new IllegalArgumentException("unsupported authorization event: " + event.type());
    }
    return true;
  }
}
