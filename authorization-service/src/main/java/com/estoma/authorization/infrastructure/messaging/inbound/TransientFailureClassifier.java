package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.port.TechnicalFailureClassifier;
import org.springframework.stereotype.Component;

@Component
final class TransientFailureClassifier {

  private final TechnicalFailureClassifier applicationClassifier;

  TransientFailureClassifier(TechnicalFailureClassifier applicationClassifier) {
    this.applicationClassifier = applicationClassifier;
  }

  boolean isTransient(Throwable failure) {
    return !(failure instanceof InvalidMessageException) && applicationClassifier.isRetryable(failure);
  }
}
