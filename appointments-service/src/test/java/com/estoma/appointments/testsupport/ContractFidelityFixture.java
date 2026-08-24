package com.estoma.appointments.testsupport;

import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Contract-faithful external producer fixture for isolated messaging scenarios. */
public final class ContractFidelityFixture {
  private ContractFidelityFixture() {}

  public static MessageEnvelope executionEvent(String type, UUID appointmentId, UUID executionId,
      long executionVersion, UUID messageId, String payload) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode body = requireObject(mapper, payload);
    putIdentity(body, "appointmentId", appointmentId);
    putIdentity(body, "washExecutionId", executionId);
    return new MessageEnvelope(messageId, type, 1, MessageKind.EVENT, "wash-execution",
        Instant.parse("2026-01-01T10:00:00Z"), UUID.randomUUID(), null, null, null, executionId,
        Math.toIntExact(executionVersion), new WireActor("SYSTEM", null, "wash-execution"), body);
  }

  public static MessageEnvelope administrativeEvent(String type, UUID aggregateId, long version,
      String payload) {
    ObjectMapper mapper = new ObjectMapper();
    return new MessageEnvelope(UUID.randomUUID(), type, 1, MessageKind.EVENT, "wash-administration",
        Instant.parse("2026-01-01T10:00:00Z"), UUID.randomUUID(), null, null, null, aggregateId,
        Math.toIntExact(version), new WireActor("SYSTEM", null, "wash-administration"),
        requireObject(mapper, payload));
  }

  private static ObjectNode requireObject(ObjectMapper mapper, String payload) {
    var node = mapper.readTree(payload);
    if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("fixture payload must be an object");
    return object;
  }

  private static void putIdentity(ObjectNode body, String field, UUID expected) {
    if (body.hasNonNull(field) && !expected.toString().equals(body.path(field).asText())) {
      throw new IllegalArgumentException(field + " does not match fixture identity");
    }
    body.put(field, expected.toString());
  }
}
