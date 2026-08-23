package com.estoma.users.application;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.query.ResolvePersonByInstitutionalEmailQuery;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.query.ResolvePersonByInstitutionalEmailData;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.PersonStatus;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.messaging.inbound.InvalidMessageException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PersonQueryDispatcher {

  private static final int RECOVERY_PAGE_LIMIT = 200;

  private final PersonRepository personRepository;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  PersonQueryDispatcher(PersonRepository personRepository, OutboxPort outbox, EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.outbox = outbox;
    this.codec = codec;
  }

  public void resolve(MessageEnvelope envelope) {
    switch (envelope.messageType()) {
      case MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL -> resolveEmail(envelope);
      case MessageTypes.GET_PERSON_STATUS_SNAPSHOT -> resolveStatusSnapshot(envelope);
      case MessageTypes.LIST_PERSON_STATUS_SNAPSHOTS_PAGE -> listStatusSnapshots(envelope);
      default -> throw new InvalidMessageException("unsupported query " + envelope.messageType());
    }
  }

  private void resolveEmail(MessageEnvelope envelope) {
    ResolvePersonByInstitutionalEmailQuery query = codec.convertPayload(envelope, ResolvePersonByInstitutionalEmailQuery.class);
    InstitutionalEmail email;
    try {
      email = InstitutionalEmail.of(query.institutionalEmail());
    } catch (PersonRejectedException malformed) {
      throw new InvalidMessageException("institutionalEmail is malformed: " + malformed.getMessage());
    }
    Optional<Person> match = personRepository.findByNormalizedEmail(email.normalized());
    ResolvePersonByInstitutionalEmailData data = match.isEmpty()
        ? ResolvePersonByInstitutionalEmailData.notFound()
        : match.get().status() == PersonStatus.ACTIVE
            ? ResolvePersonByInstitutionalEmailData.found(match.get().personId(), match.get().status().name())
            : ResolvePersonByInstitutionalEmailData.inactiveMatch(match.get().personId(), match.get().status().name());
    appendResult(envelope, MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT,
        ResultPayload.succeeded(data), data.personId(), envelope.actor());
  }

  private void resolveStatusSnapshot(MessageEnvelope envelope) {
    UUID personId = uuid(envelope.payload(), "personId");
    Optional<Person> match = personRepository.findById(personId);
    Map<String, Object> payload = new LinkedHashMap<>();
    if (match.isEmpty()) {
      payload.put("status", "NOT_FOUND");
    } else {
      payload.put("status", "FOUND");
      payload.put("data", snapshot(match.get()));
    }
    appendResult(envelope, MessageTypes.GET_PERSON_STATUS_SNAPSHOT_RESULT,
        payload, personId, new Actor.System("users"));
  }

  private void listStatusSnapshots(MessageEnvelope envelope) {
    int limit = envelope.payload().path("limit").asInt(-1);
    if (limit != RECOVERY_PAGE_LIMIT) {
      throw new InvalidMessageException("ListPersonStatusSnapshotsPage requires limit=200");
    }
    UUID cursor = envelope.payload().hasNonNull("cursor") ? uuid(envelope.payload(), "cursor") : null;
    List<Person> page = personRepository.listAfter(cursor, limit + 1);
    boolean hasMore = page.size() > limit;
    List<Person> visible = hasMore ? page.subList(0, limit) : page;
    List<Map<String, Object>> items = new ArrayList<>(visible.size());
    visible.forEach(person -> items.add(snapshot(person)));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("hasMore", hasMore);
    if (hasMore && !visible.isEmpty()) {
      data.put("nextCursor", visible.getLast().personId());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "SUCCEEDED");
    payload.put("data", data);
    appendResult(envelope, MessageTypes.LIST_PERSON_STATUS_SNAPSHOTS_PAGE_RESULT,
        payload, null, new Actor.System("users"));
  }

  private static Map<String, Object> snapshot(Person person) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("personId", person.personId());
    data.put("status", person.status().name());
    data.put("sourceVersion", person.version());
    data.put("updatedAt", person.updatedAt());
    return data;
  }

  private void appendResult(MessageEnvelope envelope, String messageType, Object payload, UUID aggregateId, Actor actor) {
    OutboxMetadata metadata = new OutboxMetadata(envelope.producer(), envelope.operationId(), actor);
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), messageType, 1, MessageKind.RESULT.name(), aggregateId, null,
        envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(payload),
        codec.writeValueAsString(metadata), Instant.now()));
  }

  private static UUID uuid(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      throw new InvalidMessageException(field + " is required");
    }
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException(field + " must be a UUID");
    }
  }
}
