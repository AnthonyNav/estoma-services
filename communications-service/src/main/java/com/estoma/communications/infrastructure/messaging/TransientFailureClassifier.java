package com.estoma.communications.infrastructure.messaging;

import java.sql.SQLTransientException;
import com.estoma.communications.application.projection.AcademicPeriodProjectionUnavailableException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

@Component
public class TransientFailureClassifier {

  public boolean isTransient(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLTransientException
          || current instanceof AcademicPeriodProjectionUnavailableException
          || current instanceof CannotAcquireLockException
          || current instanceof DataAccessResourceFailureException
          || current instanceof QueryTimeoutException) {
        return true;
      }
    }
    return false;
  }
}
