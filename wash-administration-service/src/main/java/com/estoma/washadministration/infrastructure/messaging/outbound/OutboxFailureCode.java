package com.estoma.washadministration.infrastructure.messaging.outbound;

public enum OutboxFailureCode {
  PUBLISH_CONNECTION_FAILURE,
  PUBLISH_TIMEOUT,
  PUBLISH_INTERRUPTED,
  PUBLISH_NACK,
  PUBLISH_RETURNED,
  PUBLISH_UNEXPECTED_ERROR
}
