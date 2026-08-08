package com.estoma.users.application;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.query.ResolvePersonByInstitutionalEmailQuery;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code ResolvePersonByInstitutionalEmail} (queries.md §2):
 * {@code FOUND} for an {@code ACTIVE} match, {@code INACTIVE_MATCH} for
 * an {@code INACTIVE} one, {@code NOT_FOUND} otherwise — mutually
 * exclusive by construction, since {@code
 * institutional_email_normalized} is globally unique regardless of
 * status. Reuses {@link PersonRepository#findByNormalizedEmail} and
 * {@link InstitutionalEmail}'s normalization unchanged; this is the same
 * lookup {@code NormalizedEmailDuplicatePolicy} already performs for
 * {@code CreatePerson}/{@code UpdatePerson}.
 *
 * <p>Deliberately NOT {@code @Transactional}: a {@code SELECT} followed
 * by one independent Outbox {@code INSERT} needs no atomic coupling —
 * if the process crashes between the two, no ACK has happened yet, the
 * message redelivers, and a side-effect-free lookup simply re-evaluates
 * (queries.md §2: "repetir la solicitud simplemente vuelve a evaluar la
 * misma consulta"). No Consumer Inbox either, for the same reason —
 * {@code QueryListener} never registers one.
 */
@Component
public class PersonQueryDispatcher {

  private final PersonRepository personRepository;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  PersonQueryDispatcher(PersonRepository personRepository, OutboxPort outbox, EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.outbox = outbox;
    this.codec = codec;
  }

  public void resolve(MessageEnvelope envelope) {
    ResolvePersonByInstitutionalEmailQuery query =
        codec.convertPayload(envelope, ResolvePersonByInstitutionalEmailQuery.class);

    InstitutionalEmail email;
    try {
      email = InstitutionalEmail.of(query.institutionalEmail());
    } catch (PersonRejectedException malformed) {
      // A malformed institutionalEmail is not NOT_FOUND -- queries.md
      // defines only three successful outcomes. Translated explicitly to
      // a structural failure here, rather than left to accidentally
      // escape uncaught (queries.md has no REJECTED analog to catch it
      // into) -- QueryListener routes this straight to the DLQ.
      throw new InvalidMessageException("institutionalEmail is malformed: " + malformed.getMessage());
    }

    Optional<Person> match = personRepository.findByNormalizedEmail(email.normalized());
    appendResult(envelope, toData(match));
  }

  private static ResolvePersonByInstitutionalEmailData toData(Optional<Person> match) {
    if (match.isEmpty()) {
      return ResolvePersonByInstitutionalEmailData.notFound();
    }
    Person person = match.get();
    return person.status() == PersonStatus.ACTIVE
        ? ResolvePersonByInstitutionalEmailData.found(person.personId(), person.status().name())
        : ResolvePersonByInstitutionalEmailData.inactiveMatch(person.personId(), person.status().name());
  }

  private void appendResult(MessageEnvelope envelope, ResolvePersonByInstitutionalEmailData data) {
    ResultPayload payload = ResultPayload.succeeded(data);
    OutboxMetadata metadata = new OutboxMetadata(envelope.producer(), envelope.operationId(), envelope.actor());

    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT,
            1,
            MessageKind.RESULT.name(),
            data.personId(), // aggregateId -- null for NOT_FOUND
            null, // no aggregateVersion: nothing mutated
            envelope.correlationId(),
            envelope.messageId(), // causationId: this Query's own messageId
            codec.writeValueAsString(payload),
            codec.writeValueAsString(metadata),
            Instant.now()));
  }
}
