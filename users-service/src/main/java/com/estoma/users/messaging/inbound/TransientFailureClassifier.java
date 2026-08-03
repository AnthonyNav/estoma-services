package com.estoma.users.messaging.inbound;

import com.estoma.users.domain.PersonRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Decides whether a failure raised while dispatching a command should be
 * retried (a retry-tier redelivery) or is permanent (straight to the
 * DLQ). Used only for failures that escape {@code dispatcher.dispatch(...)}
 * — by construction, a {@link PersonRejectedException} never escapes it
 * (docs/system-contract.md §7), so seeing one here would itself be a bug,
 * not a functional rejection.
 *
 * <p>Classification deliberately does not consider AMQP-related
 * exceptions: no AMQP call is ever made inside the dispatcher's
 * transaction (data-consistency-architecture.md:108-116), so the only
 * realistic failures here are database-related.
 */
@Component
public class TransientFailureClassifier {

  /**
   * @return {@code true} if {@code cause} is a transient, retryable
   *     database failure (lock timeout, serialization conflict, a
   *     momentarily unavailable connection pool — anything Spring itself
   *     classifies as {@link TransientDataAccessException}). Everything
   *     else — including {@link PersonRejectedException}, {@link
   *     InvalidMessageException}, constraint violations, and any
   *     unrecognized exception — is treated as permanent. An unclassified
   *     failure defaults to permanent rather than transient: it is safer
   *     to isolate an unexpected bug to the DLQ than to retry it forever
   *     (docs/system-contract.md §7).
   */
  public boolean isTransient(Throwable cause) {
    if (cause instanceof PersonRejectedException || cause instanceof InvalidMessageException) {
      return false;
    }
    return cause instanceof TransientDataAccessException;
  }

  /** Convenience overload accepting the broader {@link DataAccessException} umbrella. */
  public boolean isTransient(DataAccessException cause) {
    return isTransient((Throwable) cause);
  }
}
