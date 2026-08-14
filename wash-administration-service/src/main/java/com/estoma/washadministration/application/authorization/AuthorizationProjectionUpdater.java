package com.estoma.washadministration.application.authorization;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.RoleAssigned;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.RoleChanged;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessEvent;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessGranted;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessRestored;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessRevoked;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessSuspended;
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
    if (event == null
        || event.messageId() == null
        || event.accountId() == null
        || event.sourceVersion() <= 0) {
      throw new IllegalArgumentException("invalid authorization projection event");
    }
    if (event instanceof SystemAccessEvent accessEvent
        && !SYSTEM.equals(accessEvent.systemCode())) {
      return false;
    }

    Instant now = event.occurredAt() == null ? Instant.now() : event.occurredAt();
    if (!processedMessages.registerIfAbsent(CONSUMER, event.messageId(), null, now)) {
      return false;
    }

    switch (event) {
      case RoleAssigned assigned ->
          roles.upsert(
              assigned.accountId(),
              assigned.roleCode(),
              assigned.sourceVersion(),
              now);
      case RoleChanged changed ->
          roles.upsert(
              changed.accountId(),
              changed.newRoleCode(),
              changed.sourceVersion(),
              now);
      case SystemAccessGranted granted ->
          accesses.upsert(
              granted.accountId(), SYSTEM, "ACTIVE", granted.sourceVersion(), now);
      case SystemAccessRestored restored ->
          accesses.upsert(
              restored.accountId(), SYSTEM, "ACTIVE", restored.sourceVersion(), now);
      case SystemAccessSuspended suspended ->
          accesses.upsert(
              suspended.accountId(), SYSTEM, "SUSPENDED", suspended.sourceVersion(), now);
      case SystemAccessRevoked revoked ->
          accesses.upsert(
              revoked.accountId(), SYSTEM, "REVOKED", revoked.sourceVersion(), now);
    }
    return true;
  }
}
