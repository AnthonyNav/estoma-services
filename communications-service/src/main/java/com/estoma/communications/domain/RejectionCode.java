package com.estoma.communications.domain;

/**
 * The exact, closed set of functional rejection codes this service's
 * commands may produce (platform docs commands.md §2.1). No other code
 * may be introduced without updating this enum and commands.md.
 */
public enum RejectionCode {
  RECIPIENT_NOT_FOUND,
  RECIPIENT_INACTIVE,
  NO_CHANNEL_SELECTED,
  INVALID_CONTENT,
  COMMUNICATION_ALREADY_EXISTS,
  ACTOR_NOT_AUTHORIZED,
  EMAIL_DELIVERY_NOT_FOUND,
  EMAIL_DELIVERY_NOT_FAILED,
  EMAIL_ALREADY_ACCEPTED,
  EMAIL_DELIVERY_NOT_INDETERMINATE,
  INVALID_INDETERMINATE_RESOLUTION,
  RESEND_DUPLICATE_RISK_NOT_ACKNOWLEDGED,
  IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST,
  INVALID_BATCH_SIZE
}
