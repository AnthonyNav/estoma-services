package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.domain.AuthenticationRejectedException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Decides whether a failure raised while dispatching a command or event
 * should be retried (a retry-tier redelivery) or is permanent (straight
 * to the DLQ). {@link AuthenticationRejectedException} never escapes
 * {@code CommandDispatcher} by construction, so seeing one here would
 * itself be a bug -- still classified as permanent, never retried.
 *
 * <p>An unclassified failure defaults to permanent rather than transient:
 * safer to isolate an unexpected bug to the DLQ than to retry it forever.
 */
@Component
public class TransientFailureClassifier {

  public boolean isTransient(Throwable cause) {
    if (cause instanceof AuthenticationRejectedException || cause instanceof InvalidMessageException) {
      return false;
    }
    return cause instanceof TransientDataAccessException;
  }
}
