package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.query.ResolvePersonByInstitutionalEmailQuery;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.query.ResolveOutcome;
import com.estoma.users.contract.query.ResolvePersonByInstitutionalEmailData;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.ResultStatus;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.inbound.InvalidMessageException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-level (mocked ports, no Spring context, no Testcontainers) tests
 * for {@link PersonQueryDispatcher}. {@code
 * ResolvePersonByInstitutionalEmailIT} proves the real end-to-end wire
 * path; this proves the outcome-mapping and email-normalization logic in
 * isolation.
 */
class PersonQueryDispatcherTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository personRepository = mock(PersonRepository.class);
  private final OutboxPort outbox = mock(OutboxPort.class);
  private final PersonQueryDispatcher dispatcher = new PersonQueryDispatcher(personRepository, outbox, codec);

  @Test
  void anActiveMatchResolvesToFound() {
    UUID personId = UUID.randomUUID();
    Person active = activePerson(personId);
    when(personRepository.findByNormalizedEmail("juan@universidad.mx")).thenReturn(Optional.of(active));

    dispatcher.resolve(envelope("juan@universidad.mx"));

    ResolvePersonByInstitutionalEmailData data = capturedData();
    assertThat(data.outcome()).isEqualTo(ResolveOutcome.FOUND);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.status()).isEqualTo("ACTIVE");
  }

  @Test
  void anInactiveMatchResolvesToInactiveMatch() {
    UUID personId = UUID.randomUUID();
    Person inactive = activePerson(personId).deactivated(Instant.now());
    when(personRepository.findByNormalizedEmail("juan@universidad.mx")).thenReturn(Optional.of(inactive));

    dispatcher.resolve(envelope("juan@universidad.mx"));

    ResolvePersonByInstitutionalEmailData data = capturedData();
    assertThat(data.outcome()).isEqualTo(ResolveOutcome.INACTIVE_MATCH);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.status()).isEqualTo("INACTIVE");
  }

  @Test
  void noMatchResolvesToNotFoundWithoutAPersonIdOrStatus() {
    when(personRepository.findByNormalizedEmail("juan@universidad.mx")).thenReturn(Optional.empty());

    dispatcher.resolve(envelope("juan@universidad.mx"));

    OutboxAppendRequest resultRow = capturedResultRow();
    ResolvePersonByInstitutionalEmailData data = capturedData();
    assertThat(data.outcome()).isEqualTo(ResolveOutcome.NOT_FOUND);
    assertThat(data.personId()).isNull();
    assertThat(data.status()).isNull();
    // Omitted from the wire entirely, not published as null (queries.md §2)
    // -- checked on the "data" sub-object specifically, not the payload as
    // a whole, since the payload's own top-level "status" field ("SUCCEEDED")
    // is unrelated to the Person's (absent) status.
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    String dataJson = codec.writeValueAsString(payload.data());
    assertThat(dataJson).doesNotContain("personId").doesNotContain("status").isEqualTo("{\"outcome\":\"NOT_FOUND\"}");
    assertThat(resultRow.aggregateId()).isNull();
    assertThat(resultRow.aggregateVersion()).isNull();
  }

  @Test
  void normalizesEmailCasingAndWhitespaceBeforeLookingUp() {
    when(personRepository.findByNormalizedEmail("juan@universidad.mx")).thenReturn(Optional.empty());

    dispatcher.resolve(envelope("  Juan@Universidad.MX  "));

    verify(personRepository).findByNormalizedEmail("juan@universidad.mx");
  }

  @Test
  void malformedEmailIsAStructuralFailureNotNotFound() {
    assertThatThrownBy(() -> dispatcher.resolve(envelope("")))
        .isInstanceOf(InvalidMessageException.class);
    verify(outbox, never()).append(any());
    verify(personRepository, never()).findByNormalizedEmail(any());
  }

  @Test
  void theResultUsesTheCommonResultShapeAndCorrelatesToTheQuery() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findByNormalizedEmail("juan@universidad.mx"))
        .thenReturn(Optional.of(activePerson(personId)));
    MessageEnvelope envelope = envelope("juan@universidad.mx");

    dispatcher.resolve(envelope);

    OutboxAppendRequest resultRow = capturedResultRow();
    assertThat(resultRow.messageType()).isEqualTo(MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT);
    assertThat(resultRow.messageKind()).isEqualTo(MessageKind.RESULT.name());
    assertThat(resultRow.correlationId()).isEqualTo(envelope.correlationId());
    assertThat(resultRow.causationId()).isEqualTo(envelope.messageId());
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.SUCCEEDED);
    assertThat(payload.error()).isNull();
  }

  private static Person activePerson(UUID personId) {
    return Person.createNew(
        personId, "Ada", "Lovelace", null, InstitutionalEmail.of("juan@universidad.mx"), null, Instant.now());
  }

  private OutboxAppendRequest capturedResultRow() {
    ArgumentCaptor<OutboxAppendRequest> appended = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox).append(appended.capture());
    return appended.getValue();
  }

  private ResolvePersonByInstitutionalEmailData capturedData() {
    ResultPayload payload = codec.readValue(capturedResultRow().payloadJson(), ResultPayload.class);
    return codec.readValue(codec.writeValueAsString(payload.data()), ResolvePersonByInstitutionalEmailData.class);
  }

  private MessageEnvelope envelope(String institutionalEmail) {
    ResolvePersonByInstitutionalEmailQuery query = new ResolvePersonByInstitutionalEmailQuery(institutionalEmail);
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL,
        1,
        MessageKind.QUERY,
        "academic",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        null,
        null,
        null,
        new Actor.System("academic"),
        codec.toJsonNode(query));
  }
}
