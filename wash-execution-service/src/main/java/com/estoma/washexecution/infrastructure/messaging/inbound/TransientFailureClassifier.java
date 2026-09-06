package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.infrastructure.messaging.outbound.RabbitPublishException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

@Component
public final class TransientFailureClassifier {
  public boolean isTransient(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      // Spring's SQL-state fallback leaves PostgreSQL lock_not_available uncategorized.
      if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
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
