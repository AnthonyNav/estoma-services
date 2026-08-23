package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public final class ExternalEventListener {
  private static final Set<String> EVENTS = Set.of(
      "AcademicPeriodActivated", "AcademicPeriodClosed", "StudentAcademicProfileCreated", "StudentAcademicProfileActivated", "StudentAcademicProfileDeactivated", "StudentSemesterChanged",
      "CourseSectionRegistered", "CourseSectionUpdated", "CourseSectionStatusChanged", "StudentEnrolledInCourseSection", "StudentEnrollmentReactivated", "StudentRemovedFromCourseSection",
      "AccountRoleAssigned", "AccountRoleChanged", "SystemAccessGranted", "SystemAccessRestored", "SystemAccessSuspended", "SystemAccessRevoked",
      "ServiceCalendarCreated", "ServiceCalendarUpdated", "ServiceCalendarActivated", "ServiceCalendarDeactivated", "ServiceScheduleConfigured", "ServiceScheduleUpdated", "ServiceScheduleDeactivated",
      "AdministrativeClosureScheduled", "AdministrativeClosureCancelled", "CabinRegistered", "CabinUpdated", "CabinActivated", "CabinDeactivated", "CabinRetired",
      "TankRegistered", "TankUpdated", "TankActivated", "TankDeactivated", "TankRetired", "ResourcePriorityConfigured",
      "WashArrivalRegistered", "WashEntryAuthorized", "WashEntryRejected", "WashExecutionStarted", "WashExecutionCompleted", "WashExecutionCancelled",
      "OperationalResourceDisabled", "OperationalResourceRestored", "CleaningPeriodStarted", "CleaningPeriodCompleted");
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionEventDispatcher dispatcher;
  private final FailureRouter failures;
  public ExternalEventListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      ProjectionEventDispatcher dispatcher, FailureRouter failures) { this.codec = codec; this.validator = validator; this.dispatcher = dispatcher; this.failures = failures; }

  @RabbitListener(id = "appointmentsEventConsumer", queues = MessagingNames.EVENT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      dispatcher.dispatch(validator.validate(codec.readTree(message.getBody()), MessageKind.EVENT, EVENTS));
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      failures.route(message, MessagingNames.EVENT_QUEUE, false); channel.basicAck(tag, false);
    } catch (RuntimeException technical) {
      try { failures.route(message, MessagingNames.EVENT_QUEUE, true); channel.basicAck(tag, false); }
      catch (RuntimeException routingFailure) { channel.basicNack(tag, false, true); }
    }
  }
}
