package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.infrastructure.messaging.outbound.RabbitPublishException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

@Component
public final class TransientFailureClassifier {
  public boolean isTransient(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof CannotGetJdbcConnectionException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof TransientDataAccessException
          || cause instanceof RecoverableDataAccessException
          || cause instanceof RabbitPublishException) {
        return true;
      }
    }
    return false;
  }
}
