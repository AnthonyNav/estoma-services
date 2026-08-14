package com.estoma.authorization.application.port;

import java.util.function.Supplier;

/** Runs an application operation atomically without exposing Spring to Application. */
public interface TransactionRunner {

  <T> T inTransaction(Supplier<T> operation);

  default void inTransaction(Runnable operation) {
    inTransaction(
        () -> {
          operation.run();
          return null;
        });
  }
}
