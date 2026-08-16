package com.estoma.authorization.application.command;

import java.util.List;
import java.util.Optional;

public record CommandOutcome(
    Optional<ApplicationResult> result,
    List<DomainEventIntent> events,
    List<OutboundResultIntent> resultIntents,
    boolean deferred,
    String pendingReason,
    boolean duplicateDelivery) {

  public CommandOutcome {
    result = result == null ? Optional.empty() : result;
    events = List.copyOf(events == null ? List.of() : events);
    resultIntents = List.copyOf(resultIntents == null ? List.of() : resultIntents);
    if (deferred != (pendingReason != null)) {
      throw new IllegalArgumentException("deferred outcomes require exactly one pending reason");
    }
    if (deferred && result.isPresent()) {
      throw new IllegalArgumentException("deferred outcomes cannot contain a terminal result");
    }
    if (duplicateDelivery && (!events.isEmpty() || !resultIntents.isEmpty())) {
      throw new IllegalArgumentException("duplicate delivery cannot create outbound effects");
    }
  }

  /** Semantic alias used by callers that reason about all outbound Result intents. */
  public List<OutboundResultIntent> outboundResults() {
    return resultIntents;
  }

  public static CommandOutcome terminal(
      ApplicationResult result, List<DomainEventIntent> events, List<OutboundResultIntent> resultIntents) {
    return new CommandOutcome(Optional.of(result), events, resultIntents, false, null, false);
  }

  public static CommandOutcome deferred(String pendingReason) {
    return new CommandOutcome(Optional.empty(), List.of(), List.of(), true, pendingReason, false);
  }

  public static CommandOutcome duplicate() {
    return new CommandOutcome(Optional.empty(), List.of(), List.of(), false, null, true);
  }
}
