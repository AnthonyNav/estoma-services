package com.estoma.users.messaging.inbound;

/** Where {@link FailureRouter#routeAfterFailure} confirmed a failed command was republished to. */
public enum FailureRoute {
  RETRY,
  DLQ
}
