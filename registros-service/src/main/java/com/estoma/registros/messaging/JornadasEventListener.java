package com.estoma.registros.messaging;

import com.estoma.registros.contract.MessageEnvelope;
import com.estoma.registros.domain.Registro;
import com.estoma.registros.infrastructure.persistence.JdbcJornadaProyeccionRepository;
import com.estoma.registros.infrastructure.persistence.JdbcRegistroRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Keeps the local, read-only Jornada projection current — registros never
 * joins across service databases (data-model.md §2 de registros). Consumes
 * only the 3 events this V1 needs; JornadaFinalizada -> RegistroCompletado is
 * an explicit V1 simplification, not implemented yet (see README).
 */
@Component
public class JornadasEventListener {
  private static final Logger log = LoggerFactory.getLogger(JornadasEventListener.class);

  private final EnvelopeCodec codec;
  private final JdbcJornadaProyeccionRepository jornadaProyeccionRepository;
  private final JdbcRegistroRepository registroRepository;
  private final ResultAndEventPublisher publisher;

  public JornadasEventListener(
      EnvelopeCodec codec,
      JdbcJornadaProyeccionRepository jornadaProyeccionRepository,
      JdbcRegistroRepository registroRepository,
      ResultAndEventPublisher publisher) {
    this.codec = codec;
    this.jornadaProyeccionRepository = jornadaProyeccionRepository;
    this.registroRepository = registroRepository;
    this.publisher = publisher;
  }

  @RabbitListener(queues = AmqpNames.QUEUE_EVENTS)
  @Transactional
  public void consume(Message message) {
    MessageEnvelope envelope = codec.decode(message.getBody());
    log.info("evento de jornadas recibido messageType={} messageId={}", envelope.messageType(), envelope.messageId());

    switch (envelope.messageType()) {
      case "JornadaPublicada" -> onJornadaPublicada(envelope);
      case "JornadaEditada" -> onJornadaEditada(envelope);
      case "JornadaCancelada" -> onJornadaCancelada(envelope);
      case "JornadaFinalizada" -> log.info("JornadaFinalizada ignorado en V1 (sin RegistroCompletado todavía)");
      default -> throw new IllegalArgumentException("messageType desconocido en events.registros: " + envelope.messageType());
    }
  }

  private void onJornadaPublicada(MessageEnvelope envelope) {
    JsonNode payload = envelope.payload();
    List<Integer> semestres = new ArrayList<>();
    payload.path("semestresPermitidos").forEach(node -> semestres.add(node.asInt()));

    jornadaProyeccionRepository.upsertFromPublicada(
        UUID.fromString(payload.path("jornadaId").asString()),
        UUID.fromString(payload.path("tipoJornadaId").asString()),
        LocalDate.parse(payload.path("fecha").asString()),
        payload.path("cupoTotal").asInt(),
        LocalDate.parse(payload.path("fechaLimiteDocumentos").asString()),
        semestres,
        envelope.aggregateVersion() != null ? envelope.aggregateVersion() : 1);
  }

  private void onJornadaEditada(MessageEnvelope envelope) {
    UUID jornadaId = envelope.aggregateId();
    int sourceVersion = envelope.aggregateVersion() != null ? envelope.aggregateVersion() : 1;
    // JornadaEditada's payload only lists which fields changed (camposModificados) — this V1
    // projection re-derives fecha/cupoTotal from jornada-owned data is out of scope without a
    // Query; it simply bumps source_version so downstream reconciliation is at least visible.
    jornadaProyeccionRepository.applyEditada(jornadaId, null, null, null, sourceVersion);
  }

  private void onJornadaCancelada(MessageEnvelope envelope) {
    UUID jornadaId = envelope.aggregateId();
    int sourceVersion = envelope.aggregateVersion() != null ? envelope.aggregateVersion() : 1;
    jornadaProyeccionRepository.marcarCancelada(jornadaId, sourceVersion);

    List<Registro> afectados = registroRepository.cancelarPorJornadaCancelada(jornadaId);
    for (Registro registro : afectados) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("registroId", registro.registroId().toString());
      payload.put("jornadaId", registro.jornadaId().toString());
      publisher.publishEvent(
          "RegistroCanceladoPorJornadaCancelada",
          AmqpNames.ROUTING_KEY_REGISTRO_CANCELADO_JORNADA,
          envelope.correlationId(),
          envelope.messageId(),
          registro.registroId(),
          registro.version(),
          payload);
    }
  }
}
