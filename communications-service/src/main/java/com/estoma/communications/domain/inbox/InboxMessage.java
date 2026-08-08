package com.estoma.communications.domain.inbox;

import com.estoma.communications.contracts.InboxMessageStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The internal-inbox message a communication creates, at most one per
 * communication (system_contract.md "Mandatory Invariants").
 */
public final class InboxMessage {

  private final UUID inboxMessageId;
  private final UUID communicationId;
  private final String title;
  private final String body;
  private final InboxMessageStatus status;
  private final Instant createdAt;
  private final Instant expiresAt;

  private InboxMessage(
      UUID inboxMessageId,
      UUID communicationId,
      String title,
      String body,
      InboxMessageStatus status,
      Instant createdAt,
      Instant expiresAt) {
    this.inboxMessageId = requireNonNull(inboxMessageId, "inboxMessageId");
    this.communicationId = requireNonNull(communicationId, "communicationId");
    this.title = requireNonBlank(title, "title");
    this.body = requireNonBlank(body, "body");
    this.status = requireNonNull(status, "status");
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.expiresAt = expiresAt;
  }

  public static InboxMessage create(UUID inboxMessageId, UUID communicationId, String title, String body, Instant expiresAt, Instant now) {
    return new InboxMessage(inboxMessageId, communicationId, title, body, InboxMessageStatus.ACTIVE, now, expiresAt);
  }

  public static InboxMessage reconstitute(
      UUID inboxMessageId, UUID communicationId, String title, String body, InboxMessageStatus status, Instant createdAt, Instant expiresAt) {
    return new InboxMessage(inboxMessageId, communicationId, title, body, status, createdAt, expiresAt);
  }

  public UUID inboxMessageId() {
    return inboxMessageId;
  }

  public UUID communicationId() {
    return communicationId;
  }

  public String title() {
    return title;
  }

  public String body() {
    return body;
  }

  public InboxMessageStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
