package com.estoma.authorization.infrastructure.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class SpringTechnicalFailureClassifierTest {

  private final SpringTechnicalFailureClassifier classifier = new SpringTechnicalFailureClassifier();

  @Test
  void classifiesTemporaryJdbcConnectionFailuresAsRetryable() {
    assertThat(classifier.isRetryable(new CannotGetJdbcConnectionException("db unavailable")))
        .isTrue();
    assertThat(classifier.isRetryable(
            new RuntimeException(new SQLTransientConnectionException("temporary connection failure"))))
        .isTrue();
  }

  @Test
  void doesNotRetryArbitraryApplicationFailure() {
    assertThat(classifier.isRetryable(new IllegalArgumentException("invalid"))).isFalse();
  }
}
