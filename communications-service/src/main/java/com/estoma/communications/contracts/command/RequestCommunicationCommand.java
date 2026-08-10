package com.estoma.communications.contracts.command;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailContentMode;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * {@code RequestCommunication} (commands.md §3). At least one of {@code
 * inbox}/{@code email} must be present ({@code NO_CHANNEL_SELECTED}
 * otherwise). {@code communicationId} is deliberately absent: unlike
 * {@code CreateAccount}, this command's idempotency is protected by a
 * business key (commands.md §6), not a caller-chosen id -- the handler
 * generates one.
 */
public record RequestCommunicationCommand(
    UUID recipientAccountId,
    String systemCode,
    String communicationType,
    CommunicationPriority priority,
    String sourceService,
    String sourceReferenceType,
    String sourceReferenceId,
    UUID initiatedByAccountId,
    InboxContent inbox,
    EmailContent email,
    List<String> requestedChannels) {

  public RequestCommunicationCommand(UUID recipientAccountId, String systemCode, String communicationType,
      CommunicationPriority priority, String sourceService, String sourceReferenceType, String sourceReferenceId,
      UUID initiatedByAccountId, InboxContent inbox, EmailContent email) {
    this(recipientAccountId, systemCode, communicationType, priority, sourceService, sourceReferenceType,
        sourceReferenceId, initiatedByAccountId, inbox, email, null);
  }

  public record InboxContent(String title, String body, Instant expiresAt) {}

  /**
   * {@code senderCode} is a logical identifier (commands.md §3, README.md
   * §7); the infrastructure layer resolves it to a concrete sender identity
   * ({@code EmailSenderDirectory}). {@code recipientAddress} is
   * deliberately absent -- it is resolved from {@code recipient_projection}
   * ({@code institutional_email}), not supplied by the caller.
   */
  public record EmailContent(
      EmailContentMode contentMode,
      String senderCode,
      String subject,
      String textBody,
      String htmlBody,
      String templateCode,
      String templateData) {}
}
