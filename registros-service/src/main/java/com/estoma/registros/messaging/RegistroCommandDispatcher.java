package com.estoma.registros.messaging;

import com.estoma.registros.application.RegistroCommandService;
import com.estoma.registros.application.RegistroRejectedException;
import com.estoma.registros.contract.MessageEnvelope;
import com.estoma.registros.domain.Registro;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Translates each Command envelope on commands.registros into a RegistroCommandService call. */
@Component
public class RegistroCommandDispatcher {

  private static final Set<String> KNOWN_MESSAGE_TYPES =
      Set.of("CrearRegistro", "ConfirmarRegistro", "RechazarRegistro", "CancelarRegistroPorAlumno");

  private final RegistroCommandService service;
  private final ResultAndEventPublisher publisher;

  public RegistroCommandDispatcher(RegistroCommandService service, ResultAndEventPublisher publisher) {
    this.service = service;
    this.publisher = publisher;
  }

  public void dispatch(MessageEnvelope envelope) {
    String messageType = envelope.messageType();
    if (!KNOWN_MESSAGE_TYPES.contains(messageType)) {
      throw new IllegalArgumentException("messageType desconocido en commands.registros: " + messageType);
    }
    String resultType = messageType + "Result";
    try {
      switch (messageType) {
        case "CrearRegistro" -> handleCrearRegistro(envelope, resultType);
        case "ConfirmarRegistro" -> handleConfirmarRegistro(envelope, resultType);
        case "RechazarRegistro" -> handleRechazarRegistro(envelope, resultType);
        case "CancelarRegistroPorAlumno" -> handleCancelarRegistroPorAlumno(envelope, resultType);
        default -> throw new IllegalStateException("unreachable: " + messageType);
      }
    } catch (RegistroRejectedException rejected) {
      publisher.publishRejectedResult(resultType, envelope, rejected.errorCode(), rejected.getMessage());
    } catch (RuntimeException technical) {
      publisher.publishFailedResult(resultType, envelope, "REGISTROS.ERROR_INTERNO", technical.getMessage());
    }
  }

  private void handleCrearRegistro(MessageEnvelope envelope, String resultType) {
    JsonNode payload = envelope.payload();
    UUID jornadaId = requireUuid(payload, "jornadaId");
    UUID alumnoId = requireUuid(payload, "alumnoId");
    int semestreAlumno = requireInt(payload, "semestreAlumno");

    Registro registro = service.crearRegistro(jornadaId, alumnoId, semestreAlumno);

    publisher.publishSucceededResult(resultType, envelope, resultData(registro), registro.registroId(), registro.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("registroId", registro.registroId().toString());
    eventPayload.put("jornadaId", registro.jornadaId().toString());
    eventPayload.put("alumnoId", registro.alumnoId().toString());
    publisher.publishEvent(
        "RegistroCreado",
        AmqpNames.ROUTING_KEY_REGISTRO_CREADO,
        envelope.correlationId(),
        envelope.messageId(),
        registro.registroId(),
        registro.version(),
        eventPayload);
  }

  private void handleConfirmarRegistro(MessageEnvelope envelope, String resultType) {
    UUID registroId = requireAggregateId(envelope);
    Registro registro = service.confirmarRegistro(registroId);

    publisher.publishSucceededResult(resultType, envelope, resultData(registro), registro.registroId(), registro.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("registroId", registro.registroId().toString());
    eventPayload.put("jornadaId", registro.jornadaId().toString());
    eventPayload.put("alumnoId", registro.alumnoId().toString());
    publisher.publishEvent(
        "RegistroConfirmado",
        AmqpNames.ROUTING_KEY_REGISTRO_CONFIRMADO,
        envelope.correlationId(),
        envelope.messageId(),
        registro.registroId(),
        registro.version(),
        eventPayload);
  }

  private void handleRechazarRegistro(MessageEnvelope envelope, String resultType) {
    UUID registroId = requireAggregateId(envelope);
    String motivo = requireText(envelope.payload(), "motivo");
    Registro registro = service.rechazarRegistro(registroId, motivo);

    publisher.publishSucceededResult(resultType, envelope, resultData(registro), registro.registroId(), registro.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("registroId", registro.registroId().toString());
    eventPayload.put("motivo", registro.motivoRechazo());
    publisher.publishEvent(
        "RegistroRechazado",
        AmqpNames.ROUTING_KEY_REGISTRO_RECHAZADO,
        envelope.correlationId(),
        envelope.messageId(),
        registro.registroId(),
        registro.version(),
        eventPayload);
  }

  private void handleCancelarRegistroPorAlumno(MessageEnvelope envelope, String resultType) {
    UUID registroId = requireAggregateId(envelope);
    UUID alumnoId = requireUuid(envelope.payload(), "alumnoId");
    Registro registro = service.cancelarRegistroPorAlumno(registroId, alumnoId);

    publisher.publishSucceededResult(resultType, envelope, resultData(registro), registro.registroId(), registro.version());

    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("registroId", registro.registroId().toString());
    publisher.publishEvent(
        "RegistroCanceladoPorAlumno",
        AmqpNames.ROUTING_KEY_REGISTRO_CANCELADO_ALUMNO,
        envelope.correlationId(),
        envelope.messageId(),
        registro.registroId(),
        registro.version(),
        eventPayload);
  }

  private static Map<String, Object> resultData(Registro registro) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("registroId", registro.registroId().toString());
    data.put("jornadaId", registro.jornadaId().toString());
    data.put("version", registro.version());
    data.put("estado", registro.estado().name());
    return data;
  }

  private static UUID requireAggregateId(MessageEnvelope envelope) {
    if (envelope.aggregateId() == null) {
      throw new RegistroRejectedException("REGISTROS.DATOS_INVALIDOS", "aggregateId es obligatorio en el envelope");
    }
    return envelope.aggregateId();
  }

  private static String requireText(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull() || node.asString().isBlank()) {
      throw new RegistroRejectedException("REGISTROS.DATOS_INVALIDOS", "campo requerido: " + field);
    }
    return node.asString();
  }

  private static UUID requireUuid(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      throw new RegistroRejectedException("REGISTROS.DATOS_INVALIDOS", "campo inválido (uuid): " + field);
    }
  }

  private static int requireInt(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull() || !node.isNumber()) {
      throw new RegistroRejectedException("REGISTROS.DATOS_INVALIDOS", "campo requerido (numero): " + field);
    }
    return node.asInt();
  }
}
