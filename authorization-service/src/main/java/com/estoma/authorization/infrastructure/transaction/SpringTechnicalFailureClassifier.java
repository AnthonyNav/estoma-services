package com.estoma.authorization.infrastructure.transaction;

import com.estoma.authorization.application.port.TechnicalFailureClassifier;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/** Maps framework/database failure types to the transport-level retry decision. */
public final class SpringTechnicalFailureClassifier implements TechnicalFailureClassifier {

  @Override
  public boolean isRetryable(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof TransientDataAccessException
          || current instanceof RecoverableDataAccessException
          || current instanceof SQLTransientException
          || current instanceof SQLRecoverableException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
