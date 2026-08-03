package com.estoma.users.application;

import com.estoma.users.domain.RejectionCode;

/** What {@link PersonCommandDispatcher#dispatch} concluded, for metrics/logging. */
public sealed interface CommandOutcome {

  record Succeeded(int aggregateVersion) implements CommandOutcome {}

  record Rejected(RejectionCode code) implements CommandOutcome {}

  record Duplicate() implements CommandOutcome {}
}
