package com.estoma.communications.contracts;

public enum EmailDeliveryStatus {
  PENDING,
  SENDING,
  ACCEPTED,
  DELIVERED,
  RETRY_SCHEDULED,
  FAILED
}
