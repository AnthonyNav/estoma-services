package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageEnvelope;
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
  private static final Set<String> ACADEMIC_EVENTS = Set.of(
      "AcademicPeriodActivated", "AcademicPeriodClosed",
      "StudentAcademicProfileCreated", "StudentAcademicProfileActivated",
      "StudentAcademicProfileDeactivated", "StudentSemesterChanged",
      "CourseSectionRegistered", "CourseSectionUpdated", "CourseSectionStatusChanged",
      "StudentEnrolledInCourseSection", "StudentEnrollmentReactivated",
      "StudentRemovedFromCourseSection");
  private static final Set<String> AUTHORIZATION_EVENTS = Set.of(
      "AccountRoleAssigned", "AccountRoleChanged", "SystemAccessGranted",
      "SystemAccessRestored", "SystemAccessSuspended", "SystemAccessRevoked");
  private static final Set<String> ADMINISTRATION_EVENTS = Set.of(
      "ServiceCalendarCreated", "ServiceCalendarUpdated", "ServiceCalendarActivated",
      "ServiceCalendarDeactivated", "ServiceScheduleConfigured", "ServiceScheduleUpdated",
      "ServiceScheduleDeactivated", "AdministrativeClosureScheduled",
      "AdministrativeClosureCancelled", "CabinRegistered", "CabinUpdated", "CabinActivated",
      "CabinDeactivated", "CabinRetired", "TankRegistered", "TankUpdated", "TankActivated",
      "TankDeactivated", "TankRetired", "ResourcePriorityConfigured");
  private static final Set<String> EXECUTION_EVENTS = Set.of(
      "WashArrivalRegistered", "WashEntryAuthorized", "WashEntryRejected",
      "WashExecutionStarted", "WashExecutionCompleted", "WashExecutionCancelled",
      "OperationalResourceDisabled", "OperationalResourceRestored",
      "CleaningPeriodStarted", "CleaningPeriodCompleted");
  private static final Set<String> EVENTS = java.util.stream.Stream.of(
          ACADEMIC_EVENTS, AUTHORIZATION_EVENTS, ADMINISTRATION_EVENTS, EXECUTION_EVENTS)
      .flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionEventDispatcher dispatcher;
  private final FailureRouter failures;

  public ExternalEventListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      ProjectionEventDispatcher dispatcher, FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.failures = failures;
  }

  @RabbitListener(id = "appointmentsEventConsumer", queues = MessagingNames.EVENT_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      MessageEnvelope envelope = validator.validate(
          codec.readTree(message.getBody()), MessageKind.EVENT, EVENTS);
      validateProducer(envelope);
      dispatcher.dispatch(envelope);
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.EVENT_QUEUE, false, channel, tag);
    } catch (RuntimeException technical) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.EVENT_QUEUE, true, channel, tag);
    }
  }

  static void validateProducer(MessageEnvelope envelope) {
    String expected = expectedProducer(envelope.messageType());
    if (!expected.equals(envelope.producer())) {
      throw new InvalidMessageException(
          envelope.messageType() + " must be produced by " + expected);
    }
  }

  static String expectedProducer(String messageType) {
    if (ACADEMIC_EVENTS.contains(messageType)) return "academic";
    if (AUTHORIZATION_EVENTS.contains(messageType)) return "authorization";
    if (ADMINISTRATION_EVENTS.contains(messageType)) return "wash-administration";
    if (EXECUTION_EVENTS.contains(messageType)) return "wash-execution";
    throw new InvalidMessageException("unsupported external event " + messageType);
  }
}
