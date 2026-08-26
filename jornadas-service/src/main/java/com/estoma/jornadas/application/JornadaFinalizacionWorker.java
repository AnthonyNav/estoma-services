package com.estoma.jornadas.application;

import com.estoma.jornadas.domain.Jornada;
import com.estoma.jornadas.infrastructure.persistence.JdbcJornadaRepository;
import com.estoma.jornadas.messaging.AmqpNames;
import com.estoma.jornadas.messaging.ResultAndEventPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves PRD Q11 for jornadas: JornadaFinalizada is driven by this
 * scheduled worker, not calculated on read. See jornadas/events.md §4
 * (estomatologia-platform-docs) for the normative decision; {@code
 * registros} still owes an equivalent worker for its own Q11 case
 * (cancellation for missing documents before the deadline) — not covered
 * here.
 */
@Component
public class JornadaFinalizacionWorker {
  private static final Logger log = LoggerFactory.getLogger(JornadaFinalizacionWorker.class);

  private final JdbcJornadaRepository jornadaRepository;
  private final ResultAndEventPublisher publisher;

  public JornadaFinalizacionWorker(JdbcJornadaRepository jornadaRepository, ResultAndEventPublisher publisher) {
    this.jornadaRepository = jornadaRepository;
    this.publisher = publisher;
  }

  @Scheduled(fixedDelayString = "${estoma.jornadas.finalizacion.fixed-delay:PT1M}")
  @Transactional
  public void finalizarJornadasVencidas() {
    List<Jornada> finalizadas = jornadaRepository.finalizarVencidas();
    for (Jornada jornada : finalizadas) {
      log.info("Jornada finalizada automáticamente jornadaId={} fecha={}", jornada.jornadaId(), jornada.fecha());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("jornadaId", jornada.jornadaId().toString());
      publisher.publishEvent(
          "JornadaFinalizada",
          AmqpNames.ROUTING_KEY_JORNADA_FINALIZADA,
          UUID.randomUUID(),
          null,
          jornada.jornadaId(),
          jornada.version(),
          payload);
    }
  }
}
