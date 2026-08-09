package com.estoma.authentication.infrastructure.messaging.inbound;

/** Where {@link FailureRouter#routeAfterFailure} confirmed a failed message was republished to. */
public enum FailureRoute {
  RETRY,
  DLQ
}
