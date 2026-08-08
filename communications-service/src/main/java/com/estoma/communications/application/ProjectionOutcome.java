package com.estoma.communications.application;

/** What {@link ProjectionDispatcher#dispatch} concluded, for metrics/logging. */
public sealed interface ProjectionOutcome {

  record Applied() implements ProjectionOutcome {}

  record Duplicate() implements ProjectionOutcome {}
}
