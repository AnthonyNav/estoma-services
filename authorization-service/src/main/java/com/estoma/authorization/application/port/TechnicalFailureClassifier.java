package com.estoma.authorization.application.port;

/**
 * Hexagonal policy for deciding whether an infrastructure failure must escape Application so the
 * transport adapter can retry it, or can be persisted as a terminal FAILED Result.
 */
public interface TechnicalFailureClassifier {

  boolean isRetryable(Throwable failure);
}
