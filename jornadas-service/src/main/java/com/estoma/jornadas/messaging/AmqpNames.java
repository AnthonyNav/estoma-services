package com.estoma.jornadas.messaging;

/** Canonical RabbitMQ names owned or used by jornadas-service. */
public final class AmqpNames {

  private AmqpNames() {}

  public static final String EXCHANGE_COMMANDS = "platform.commands";
  public static final String EXCHANGE_RESULTS = "platform.results";
  public static final String EXCHANGE_EVENTS = "platform.events";
  public static final String EXCHANGE_DLX = "platform.dlx";

  /** Platform logicalComponentId. The deployable remains named jornadas-service. */
  public static final String LOGICAL_COMPONENT_ID = "jornadas";

  public static final String QUEUE_COMMANDS = "commands.jornadas";
  public static final String ROUTING_KEY_COMMANDS = LOGICAL_COMPONENT_ID;
  public static final String QUEUE_COMMANDS_DLQ = "commands.jornadas.dlq";
  public static final String ROUTING_KEY_DLQ = "dlq.commands.jornadas";

  /** results.bff is owned and declared by the BFF, not by jornadas — only referenced here. */
  public static final String ROUTING_KEY_RESULTS_BFF = "bff";

  public static final String ROUTING_KEY_JORNADA_PUBLICADA = "jornadas.jornada.publicada";
  public static final String ROUTING_KEY_JORNADA_EDITADA = "jornadas.jornada.editada";
  public static final String ROUTING_KEY_JORNADA_CANCELADA = "jornadas.jornada.cancelada";
  public static final String ROUTING_KEY_JORNADA_FINALIZADA = "jornadas.jornada.finalizada";
  public static final String ROUTING_KEY_TIPO_JORNADA_CONFIGURADO =
      "jornadas.tipo-jornada.configurado";
}
