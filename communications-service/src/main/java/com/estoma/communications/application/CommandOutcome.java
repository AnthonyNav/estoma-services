package com.estoma.communications.application;

import com.estoma.communications.domain.RejectionCode;

/** What {@link CommandDispatcher#dispatch} concluded, for metrics/logging. */
public sealed interface CommandOutcome {

  record Succeeded(Integer aggregateVersion) implements CommandOutcome {}

  record Rejected(RejectionCode code) implements CommandOutcome {}

  record Duplicate() implements CommandOutcome {}
}
