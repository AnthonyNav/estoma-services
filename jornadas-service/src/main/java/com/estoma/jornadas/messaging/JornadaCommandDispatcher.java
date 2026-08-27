package com.estoma.jornadas.messaging;

import com.estoma.jornadas.application.EditarJornadaFields;
import com.estoma.jornadas.application.JornadaCommandService;
import com.estoma.jornadas.application.JornadaRejectedException;
import com.estoma.jornadas.contract.MessageEnvelope;
import com.estoma.jornadas.domain.Jornada;
import com.estoma.jornadas.infrastructure.persistence.JdbcTipoJornadaRepository.TipoJornadaSaved;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Translates each Command envelope on commands.jornadas into a JornadaCommandService call. */
@Component
public class JornadaCommandDispatcher {

  private static final Set<String> KNOWN_MESSAGE_TYPES =
      Set.of("PublicarJornada", "EditarJornada", "CancelarJornada", "ConfigurarTipoJornada");

  private final JornadaCommandService service;
  private final ResultAndEventPublisher publisher;

  public JornadaCommandDispatcher(JornadaCommandService service, ResultAndEventPublisher publisher) {
    this.service = service;
    this.publisher = publisher;
  }

  public void dispatch(MessageEnvelope envelope) {
    String messageType = envelope.messageType();
    if (!KNOWN_MESSAGE_TYPES.contains(messageType)) {
      throw new IllegalArgumentException("messageType desconocido en commands.jornadas: " + messageType);
    }
    String resultType = messageType + "Result";
    try {
      switch (messageType) {
        case "PublicarJornada" -> handlePublicarJornada(envelope, resultType);
        case "EditarJornada" -> handleEditarJornada(envelope, resultType);
        case "CancelarJornada" -> handleCancelarJornada(envelope, resultType);
        case "ConfigurarTipoJornada" -> handleConfigurarTipoJornada(envelope, resultType);
        default -> throw new IllegalStateException("unreachable: " + messageType);
      }
    } catch (JornadaRejectedException rejected) {
      publisher.publishRejectedResult(resultType, envelope, rejected.errorCode(), rejected.getMessage());
    } catch (RuntimeException technical) {
      publisher.publishFailedResult(resultType, envelope, "JORNADAS.ERROR_INTERNO", technical.getMessage());
    }
  }

  private void handlePublicarJornada(MessageEnvelope envelope, String resultType) {
    JsonNode payload = envelope.payload();
    UUID tipoJornadaId = requireUuid(payload, "tipoJornadaId");
    String nombre = requireText(payload, "nombre");
    LocalDate fecha = requireLocalDate(payload, "fecha");
    LocalTime horaInicio = requireLocalTime(payload, "horaInicio");
    LocalTime horaFin = requireLocalTime(payload, "horaFin");
    String lugar = requireText(payload, "lugar");
    int cupoTotal = requireInt(payload, "cupoTotal");
    LocalDate fechaLimiteDocumentos = requireLocalDate(payload, "fechaLimiteDocumentos");
    String descripcion = optionalText(payload, "descripcion");

    Jornada jornada =
        service.publicarJornada(
            tipoJornadaId, nombre, fecha, horaInicio, horaFin, lugar, cupoTotal, fechaLimiteDocumentos, descripcion);

    publisher.publishSucceededResult(
        resultType, envelope, resultData(jornada), jornada.jornadaId(), jornada.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("jornadaId", jornada.jornadaId().toString());
    eventPayload.put("tipoJornadaId", jornada.tipoJornadaId().toString());
    eventPayload.put("nombre", jornada.nombre());
    eventPayload.put("fecha", jornada.fecha().toString());
    eventPayload.put("horaInicio", jornada.horaInicio().toString());
    eventPayload.put("horaFin", jornada.horaFin().toString());
    eventPayload.put("lugar", jornada.lugar());
    eventPayload.put("cupoTotal", jornada.cupoTotal());
    eventPayload.put("fechaLimiteDocumentos", jornada.fechaLimiteDocumentos().toString());
    eventPayload.put("descripcion", jornada.descripcion());
    eventPayload.put("semestresPermitidos", service.semestresPermitidos(jornada.tipoJornadaId()));
    publisher.publishEvent(
        "JornadaPublicada",
        AmqpNames.ROUTING_KEY_JORNADA_PUBLICADA,
        envelope.correlationId(),
        envelope.messageId(),
        jornada.jornadaId(),
        jornada.version(),
        eventPayload);
  }

  private void handleEditarJornada(MessageEnvelope envelope, String resultType) {
    UUID jornadaId = requireAggregateId(envelope);
    int expectedVersion = requireAggregateVersion(envelope);
    JsonNode payload = envelope.payload();
    EditarJornadaFields fields =
        new EditarJornadaFields(
            optionalText(payload, "nombre"),
            optionalLocalDate(payload, "fecha"),
            optionalLocalTime(payload, "horaInicio"),
            optionalLocalTime(payload, "horaFin"),
            optionalText(payload, "lugar"),
            optionalInt(payload, "cupoTotal"),
            optionalLocalDate(payload, "fechaLimiteDocumentos"),
            optionalText(payload, "descripcion"));

    Jornada jornada = service.editarJornada(jornadaId, expectedVersion, fields);

    publisher.publishSucceededResult(
        resultType, envelope, resultData(jornada), jornada.jornadaId(), jornada.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("jornadaId", jornada.jornadaId().toString());
    eventPayload.put("camposModificados", fields.camposPresentes());
    publisher.publishEvent(
        "JornadaEditada",
        AmqpNames.ROUTING_KEY_JORNADA_EDITADA,
        envelope.correlationId(),
        envelope.messageId(),
        jornada.jornadaId(),
        jornada.version(),
        eventPayload);
  }

  private void handleCancelarJornada(MessageEnvelope envelope, String resultType) {
    UUID jornadaId = requireAggregateId(envelope);
    String motivo = requireText(envelope.payload(), "motivo");

    Jornada jornada = service.cancelarJornada(jornadaId, motivo);

    publisher.publishSucceededResult(
        resultType, envelope, resultData(jornada), jornada.jornadaId(), jornada.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("jornadaId", jornada.jornadaId().toString());
    eventPayload.put("motivo", jornada.motivoCancelacion());
    publisher.publishEvent(
        "JornadaCancelada",
        AmqpNames.ROUTING_KEY_JORNADA_CANCELADA,
        envelope.correlationId(),
        envelope.messageId(),
        jornada.jornadaId(),
        jornada.version(),
        eventPayload);
  }

  private void handleConfigurarTipoJornada(MessageEnvelope envelope, String resultType) {
    JsonNode payload = envelope.payload();
    UUID tipoJornadaId = optionalUuid(payload, "tipoJornadaId");
    String nombre = requireText(payload, "nombre");
    List<Integer> semestresPermitidos = requireIntArray(payload, "semestresPermitidos");

    TipoJornadaSaved saved = service.configurarTipoJornada(tipoJornadaId, nombre, semestresPermitidos);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tipoJornadaId", saved.tipoJornadaId().toString());
    data.put("version", saved.version());
    publisher.publishSucceededResult(resultType, envelope, data, saved.tipoJornadaId(), saved.version());
    // No hay evento de dominio publicado para ConfigurarTipoJornada — ver jornadas/events.md §2.
  }

  private static Map<String, Object> resultData(Jornada jornada) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("jornadaId", jornada.jornadaId().toString());
    data.put("version", jornada.version());
    data.put("estado", jornada.estado().name());
    return data;
  }

  private static UUID requireAggregateId(MessageEnvelope envelope) {
    if (envelope.aggregateId() == null) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "aggregateId es obligatorio en el envelope");
    }
    return envelope.aggregateId();
  }

  private static int requireAggregateVersion(MessageEnvelope envelope) {
    if (envelope.aggregateVersion() == null) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "aggregateVersion es obligatorio en el envelope");
    }
    return envelope.aggregateVersion();
  }

  private static String requireText(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull() || node.asString().isBlank()) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo requerido: " + field);
    }
    return node.asString();
  }

  private static String optionalText(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asString();
  }

  private static UUID requireUuid(JsonNode payload, String field) {
    return parseUuid(field, requireText(payload, field));
  }

  private static UUID optionalUuid(JsonNode payload, String field) {
    String text = optionalText(payload, field);
    return text == null ? null : parseUuid(field, text);
  }

  private static UUID parseUuid(String field, String text) {
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo inválido (uuid): " + field);
    }
  }

  private static int requireInt(JsonNode payload, String field) {
    Integer value = optionalInt(payload, field);
    if (value == null) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo requerido: " + field);
    }
    return value;
  }

  private static Integer optionalInt(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isNumber()) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo inválido (numero): " + field);
    }
    return node.asInt();
  }

  private static LocalDate requireLocalDate(JsonNode payload, String field) {
    LocalDate value = optionalLocalDate(payload, field);
    if (value == null) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo requerido: " + field);
    }
    return value;
  }

  private static LocalDate optionalLocalDate(JsonNode payload, String field) {
    String text = optionalText(payload, field);
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException invalid) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo inválido (fecha ISO-8601): " + field);
    }
  }

  private static LocalTime requireLocalTime(JsonNode payload, String field) {
    LocalTime value = optionalLocalTime(payload, field);
    if (value == null) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo requerido: " + field);
    }
    return value;
  }

  private static LocalTime optionalLocalTime(JsonNode payload, String field) {
    String text = optionalText(payload, field);
    if (text == null) {
      return null;
    }
    try {
      return LocalTime.parse(text);
    } catch (DateTimeParseException invalid) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo inválido (hora HH:mm): " + field);
    }
  }

  private static List<Integer> requireIntArray(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull() || !node.isArray() || node.isEmpty()) {
      throw new JornadaRejectedException("JORNADAS.DATOS_INVALIDOS", "campo requerido (arreglo no vacío): " + field);
    }
    List<Integer> values = new ArrayList<>();
    node.forEach(item -> values.add(item.asInt()));
    return values;
  }
}
