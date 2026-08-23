package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public final class CommandListener {
  private static final Set<String> COMMANDS = Set.of("ScheduleAppointment", "CancelAppointment",
      "GrantExceptionalAppointmentAuthorization", "CancelExceptionalAppointmentAuthorization",
      "CreateAppointmentTimeSlot", "CancelAppointmentTimeSlot");
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandMessageMapper mapper;
  private final AppointmentCommandDispatcher dispatcher;
  private final FailureRouter failures;

  public CommandListener(EnvelopeJsonCodec codec, EnvelopeValidator validator, CommandMessageMapper mapper,
      AppointmentCommandDispatcher dispatcher, FailureRouter failures) {
    this.codec = codec; this.validator = validator; this.mapper = mapper; this.dispatcher = dispatcher; this.failures = failures;
  }

  @RabbitListener(id = "appointmentsCommandConsumer", queues = MessagingNames.COMMAND_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var envelope = validator.validate(codec.readTree(message.getBody()), MessageKind.COMMAND, COMMANDS);
      ApplicationResult result = dispatcher.dispatch(mapper.map(envelope));
      if (result.status() == ApplicationResult.Status.DEFERRED) {
        failures.route(message, MessagingNames.COMMAND_QUEUE, true);
      }
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      failures.route(message, MessagingNames.COMMAND_QUEUE, false);
      channel.basicAck(tag, false);
    } catch (RuntimeException technical) {
      try {
        failures.route(message, MessagingNames.COMMAND_QUEUE, true);
        channel.basicAck(tag, false);
      } catch (RuntimeException routingFailure) {
        channel.basicNack(tag, false, true);
      }
    }
  }
}
