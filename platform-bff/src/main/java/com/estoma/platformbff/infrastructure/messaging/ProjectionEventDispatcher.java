package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionEventRuntime;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Infrastructure adapter: decodes the wire tree and delegates to the application trust boundary. */
@Component
public class ProjectionEventDispatcher {
  public enum DispatchOutcome { APPLIED, UNSUPPORTED, UNTRUSTED }

  private final ProjectionEventRuntime runtime;

  public ProjectionEventDispatcher(ProjectionEventRuntime runtime) {
    this.runtime = runtime;
  }

  public DispatchOutcome dispatch(EventEnvelope event) {
    if (event == null || event.messageKind() != MessageKind.EVENT || event.payload() == null
        || !event.payload().isObject()) {
      return DispatchOutcome.UNTRUSTED;
    }
    try {
      var outcome = runtime.dispatch(new ProjectionEvent(
          event.messageId(), event.messageType(), event.messageVersion(), event.producer(),
          event.occurredAt(), event.correlationId(), event.causationId(), event.aggregateId(),
          event.aggregateVersion(), object(event.payload())));
      return switch (outcome) {
        case APPLIED -> DispatchOutcome.APPLIED;
        case UNSUPPORTED -> DispatchOutcome.UNSUPPORTED;
        case UNTRUSTED -> DispatchOutcome.UNTRUSTED;
      };
    } catch (IllegalArgumentException invalid) {
      return DispatchOutcome.UNTRUSTED;
    }
  }

  private static Map<String, Object> object(JsonNode node) {
    Map<String, Object> result = new HashMap<>();
    node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
    return result;
  }

  private static Object value(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isObject()) return object(node);
    if (node.isArray()) {
      var values = new ArrayList<>();
      node.forEach(child -> values.add(value(child)));
      return values;
    }
    if (node.isBoolean()) return node.asBoolean();
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isNumber()) return node.doubleValue();
    return node.asText();
  }
}
