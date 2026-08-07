package com.estoma.users.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

class TransientFailureClassifierTest {

  private final TransientFailureClassifier classifier = new TransientFailureClassifier();

  @Test
  void cannotAcquireLockIsTransient() {
    assertThat(classifier.isTransient(new CannotAcquireLockException("lock timeout"))).isTrue();
  }

  @Test
  void queryTimeoutIsTransient() {
    assertThat(classifier.isTransient(new QueryTimeoutException("query timeout"))).isTrue();
  }

  @Test
  void transientDataAccessResourceFailureIsTransient() {
    assertThat(
            classifier.isTransient(
                new TransientDataAccessResourceException("connection pool momentarily exhausted")))
        .isTrue();
  }

  @Test
  void personRejectedExceptionIsNeverTransient() {
    // Seeing one here at all would itself be a bug -- it should never
    // escape the dispatcher's transaction (docs/system-contract.md §7).
    assertThat(
            classifier.isTransient(
                new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "not found")))
        .isFalse();
  }

  @Test
  void invalidMessageExceptionIsNeverTransient() {
    assertThat(classifier.isTransient(new InvalidMessageException("bad envelope"))).isFalse();
  }

  @Test
  void dataIntegrityViolationIsPermanent() {
    assertThat(
            classifier.isTransient(
                new DataIntegrityViolationException("constraint violated", new SQLException())))
        .isFalse();
  }

  @Test
  void anUnrecognizedExceptionDefaultsToPermanent() {
    assertThat(classifier.isTransient(new IllegalStateException("something unexpected"))).isFalse();
  }

  @Test
  void nullPointerExceptionDefaultsToPermanent() {
    assertThat(classifier.isTransient(new NullPointerException())).isFalse();
  }
}
