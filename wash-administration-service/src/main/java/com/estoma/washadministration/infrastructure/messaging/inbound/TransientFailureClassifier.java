package com.estoma.washadministration.infrastructure.messaging.inbound;

import java.sql.SQLTransientException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

@Component
public class TransientFailureClassifier {

  public boolean isTransient(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof CannotAcquireLockException
          || cause instanceof SQLTransientException
          || cause instanceof AmqpConnectException) {
        return true;
      }
    }
    return false;
  }
}
