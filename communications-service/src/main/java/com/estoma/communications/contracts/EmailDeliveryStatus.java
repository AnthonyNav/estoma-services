package com.estoma.communications.contracts;

public enum EmailDeliveryStatus {
  WAITING_FOR_RECIPIENT,
  PENDING,
  SENDING,
  ACCEPTED,
  DELIVERED,
  RETRY_SCHEDULED,
  INDETERMINATE,
  FAILED
}
