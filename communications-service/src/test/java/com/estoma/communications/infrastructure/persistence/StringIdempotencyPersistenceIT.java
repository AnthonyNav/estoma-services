package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StringIdempotencyPersistenceIT extends AbstractPostgresIT {

  @Autowired private CommunicationRepository repository;

  @Test
  void roundTripsANonUuidFunctionalKeyWithoutNormalizationLoss() {
    String key = "communications:request:" + UUID.randomUUID();
    Communication communication = Communication.requested(
        UUID.randomUUID(), UUID.randomUUID(), "GLOBAL", "TEST", CommunicationPriority.NORMAL,
        "bff", "RequestCommunication", "ref-" + UUID.randomUUID(), null,
        List.of("INBOX"), UUID.randomUUID(), key, "fingerprint", UUID.randomUUID(), null, Instant.now());

    repository.insert(communication);
    Communication restored = repository.findById(communication.communicationId()).orElseThrow();

    assertThat(restored.idempotencyKey()).isEqualTo(key);
    assertThat(repository.findBySourceAndIdempotency("bff", key))
        .get().extracting(Communication::communicationId).isEqualTo(communication.communicationId());
  }
}
