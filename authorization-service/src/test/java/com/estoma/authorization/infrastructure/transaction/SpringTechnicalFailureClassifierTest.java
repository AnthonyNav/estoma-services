package com.estoma.authorization.infrastructure.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLRecoverableException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class SpringTechnicalFailureClassifierTest {

  private final SpringTechnicalFailureClassifier classifier = new SpringTechnicalFailureClassifier();

  @Test
  void classifiesSpringAndJdbcTransientFailuresAsRetryable() {
    assertThat(classifier.isRetryable(
            new TransientDataAccessResourceException("database temporarily unavailable")))
        .isTrue();
    assertThat(classifier.isRetryable(
            new IllegalStateException("wrapper", new SQLRecoverableException("connection reset"))))
        .isTrue();
  }

  @Test
  void leavesPermanentUnexpectedFailuresForDurableFailedResult() {
    assertThat(classifier.isRetryable(new IllegalStateException("permanent local failure")))
        .isFalse();
  }
}
