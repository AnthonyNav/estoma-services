package com.estoma.communications.contracts;

public enum CommunicationsEvent {
  COMMUNICATION_REQUESTED("CommunicationRequested"),
  INBOX_MESSAGE_CREATED("InboxMessageCreated"),
  INBOX_VISITED("InboxVisited"),
  INBOX_MESSAGE_EXPIRED("InboxMessageExpired"),
  EMAIL_DELIVERY_ACCEPTED("EmailDeliveryAccepted"),
  EMAIL_DELIVERY_RETRY_SCHEDULED("EmailDeliveryRetryScheduled"),
  EMAIL_PROVIDER_FALLBACK_ACTIVATED("EmailProviderFallbackActivated"),
  EMAIL_DELIVERY_FAILED("EmailDeliveryFailed"),
  EMAIL_DELIVERY_DELIVERED("EmailDeliveryDelivered"),
  COMMUNICATION_COMPLETED("CommunicationCompleted"),
  COMMUNICATION_FAILED("CommunicationFailed");

  private final String wireName;

  CommunicationsEvent(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
