package com.estoma.registros.messaging;

/** Canonical RabbitMQ names owned or used by registros-service. */
public final class AmqpNames {

  private AmqpNames() {}

  public static final String EXCHANGE_COMMANDS = "platform.commands";
  public static final String EXCHANGE_RESULTS = "platform.results";
  public static final String EXCHANGE_EVENTS = "platform.events";
  public static final String EXCHANGE_DLX = "platform.dlx";

  /** Platform logicalComponentId. The deployable remains named registros-service. */
  public static final String LOGICAL_COMPONENT_ID = "registros";

  public static final String QUEUE_COMMANDS = "commands.registros";
  public static final String ROUTING_KEY_COMMANDS = LOGICAL_COMPONENT_ID;
  public static final String QUEUE_COMMANDS_DLQ = "commands.registros.dlq";
  public static final String ROUTING_KEY_DLQ = "dlq.commands.registros";

  /** Consumes jornadas-service's domain events to keep the local Jornada projection current. */
  public static final String QUEUE_EVENTS = "events.registros";
  public static final String ROUTING_KEY_JORNADAS_EVENTS = "jornadas.#";
  public static final String QUEUE_EVENTS_DLQ = "events.registros.dlq";
  public static final String ROUTING_KEY_EVENTS_DLQ = "dlq.events.registros";

  /** results.bff is owned and declared by the BFF, not by registros — only referenced here. */
  public static final String ROUTING_KEY_RESULTS_BFF = "bff";

  public static final String ROUTING_KEY_REGISTRO_CREADO = "registros.registro.creado";
  public static final String ROUTING_KEY_REGISTRO_CONFIRMADO = "registros.registro.confirmado";
  public static final String ROUTING_KEY_REGISTRO_RECHAZADO = "registros.registro.rechazado";
  public static final String ROUTING_KEY_REGISTRO_CANCELADO_ALUMNO = "registros.registro.cancelado-alumno";
  public static final String ROUTING_KEY_REGISTRO_CANCELADO_JORNADA = "registros.registro.cancelado-jornada-cancelada";
  public static final String ROUTING_KEY_REGISTRO_COMPLETADO = "registros.registro.completado";
}
