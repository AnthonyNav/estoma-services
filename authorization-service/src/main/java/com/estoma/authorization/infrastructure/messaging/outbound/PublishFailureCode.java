package com.estoma.authorization.infrastructure.messaging.outbound;

enum PublishFailureCode {
  PUBLISH_CONNECTION_FAILURE,
  PUBLISH_TIMEOUT,
  PUBLISH_INTERRUPTED,
  PUBLISH_NACK,
  PUBLISH_RETURNED,
  PUBLISH_UNEXPECTED_ERROR,
  OUTBOX_CONTRACT_INVALID
}
