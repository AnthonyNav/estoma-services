package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.estoma.appointments.contracts.WireActor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Claims in a short transaction, publishes with confirm, then fences the claim. */
@Component
public final class OutboxRelay {
  private static final Set<String> BFF_RESULTS = Set.of(
      "ScheduleAppointmentSucceeded", "ScheduleAppointmentRejected",
      "CancelAppointmentSucceeded", "CancelAppointmentRejected",
      "CreateAppointmentTimeSlotSucceeded", "CreateAppointmentTimeSlotRejected",
      "CancelAppointmentTimeSlotSucceeded", "CancelAppointmentTimeSlotRejected",
      "GrantExceptionalAppointmentAuthorizationSucceeded",
      "GrantExceptionalAppointmentAuthorizationRejected",
      "CancelExceptionalAppointmentAuthorizationSucceeded",
      "CancelExceptionalAppointmentAuthorizationRejected");
  private static final Set<String> EXECUTION_QUERY_RESULTS = Set.of(
      "GetAppointmentExecutionContextResult", "ListAppointmentExecutionContextsPageResult");

  private final OutboxRepository outbox;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public OutboxRelay(OutboxRepository outbox, ConfirmedRabbitPublisher publisher, EnvelopeJsonCodec codec,
      MessagingProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.codec = codec;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-poll-interval:1s}")
  public void scheduledRelay() {
    if (properties.outboxRelayEnabled()) relay();
  }

  public void relay() {
    String worker = UUID.randomUUID().toString();
    Instant now = clock.instant();
    List<OutboxRepository.OutboxMessage> messages = transactions.execute(status -> outbox.claimBatch(
        properties.outboxClaimBatchSize(), worker, now, now.plus(properties.outboxLease())));
    if (messages == null) return;
    for (var message : messages) {
      try {
        publish(message);
        transactions.execute(status -> outbox.markPublished(message.messageId(), worker, clock.instant()));
      } catch (RabbitPublishException exception) {
        int attempts = message.attempts() + 1;
        transactions.execute(status -> outbox.markFailed(message.messageId(), worker, exception.getMessage(),
            clock.instant().plusSeconds(Math.min(300L, 1L << Math.min(attempts, 8)))));
      } catch (RuntimeException permanent) {
        transactions.execute(status -> outbox.quarantine(message.messageId(), worker,
            "INVALID_OUTBOX_CONTRACT: " + permanent.getMessage(), clock.instant()));
      }
    }
  }

  private void publish(OutboxRepository.OutboxMessage message) {
    MessageKind kind = MessageKind.valueOf(message.messageKind());
    String exchange = switch (kind) {
      case COMMAND -> MessagingNames.COMMANDS_EXCHANGE;
      case EVENT -> MessagingNames.EVENTS_EXCHANGE;
      case RESULT -> MessagingNames.RESULTS_EXCHANGE;
      case QUERY -> MessagingNames.QUERIES_EXCHANGE;
    };
    String route = routingKey(kind, message.messageType());
    MessageProperties wireProperties = new MessageProperties();
    wireProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    wireProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    wireProperties.setMessageId(message.messageId().toString());

    JsonNode payload = codec.readTree(message.payloadJson());
    String idempotencyKey = kind == MessageKind.COMMAND
        ? commandIdempotencyKey(message.messageType(), payload, message.messageId()) : null;
    MessageEnvelope envelope = new MessageEnvelope(message.messageId(), message.messageType(),
        message.messageVersion(), kind, MessagingNames.LOGICAL_COMPONENT_ID, message.occurredAt(),
        message.correlationId(), message.causationId(), message.operationId(), idempotencyKey,
        message.aggregateId(), message.aggregateVersion() == null ? null : Math.toIntExact(message.aggregateVersion()),
        new WireActor("SYSTEM", null, MessagingNames.LOGICAL_COMPONENT_ID), payload);
    Message wire = new Message(codec.encode(envelope), wireProperties);
    if (kind == MessageKind.EVENT) publisher.publishEvent(exchange, route, wire);
    else publisher.publishRequiredRoute(exchange, route, wire);
  }

  static String commandIdempotencyKey(String messageType, JsonNode payload, UUID fallback) {
    return switch (messageType) {
      case "RecalculateAppointmentCapacityForTimeSlot" -> required(payload, "sourceEventId") + ":" + required(payload, "slotId");
      case "MarkAppointmentAsMissed" -> required(payload, "appointmentId") + ":" + requiredNumber(payload, "expectedVersion");
      case "CreateAppointmentTimeSlot" -> required(payload, "slotId");
      default -> fallback.toString();
    };
  }

  static String routingKey(MessageKind kind, String messageType) {
    if (kind == MessageKind.COMMAND) return MessagingNames.LOGICAL_COMPONENT_ID;
    if (kind == MessageKind.RESULT) {
      if (BFF_RESULTS.contains(messageType)) return "bff";
      if (EXECUTION_QUERY_RESULTS.contains(messageType)) return "wash-execution";
      throw new IllegalArgumentException("unknown Result destination for " + messageType);
    }
    if (kind == MessageKind.QUERY) {
      return switch (messageType) {
        case "GetAppointmentAcademicConfiguration", "ListStudentAppointmentAcademicSnapshotsPage" -> "academic";
        case "ListAccountAuthorizationSnapshotsPage" -> "authorization";
        case "GetWashAdministrationConfiguration" -> "wash-administration";
        case "ListWashExecutionIntegrationContextsPage" -> "wash-execution";
        default -> throw new IllegalArgumentException("unknown Query destination for " + messageType);
      };
    }
    return eventRoutingKey(messageType);
  }

  static String eventRoutingKey(String messageType) {
    return switch (messageType) {
      case "AppointmentTimeSlotCreated" -> "appointments.appointment-time-slot.created";
      case "AppointmentTimeSlotCapacityRecalculated" -> "appointments.appointment-time-slot.capacity-recalculated";
      case "AppointmentTimeSlotCapacityReduced" -> "appointments.appointment-time-slot.capacity-reduced";
      case "AppointmentTimeSlotCancelled" -> "appointments.appointment-time-slot.cancelled";
      case "AppointmentScheduled" -> "appointments.appointment.scheduled";
      case "AppointmentCancelled" -> "appointments.appointment.cancelled";
      case "AppointmentMissed" -> "appointments.appointment.missed";
      case "AppointmentEntryRejected" -> "appointments.appointment.entry-rejected";
      case "AppointmentStarted" -> "appointments.appointment.started";
      case "AppointmentCompleted" -> "appointments.appointment.completed";
      case "ExceptionalAppointmentAuthorizationGranted" ->
          "appointments.exceptional-appointment-authorization.granted";
      case "ExceptionalAppointmentAuthorizationCancelled" ->
          "appointments.exceptional-appointment-authorization.cancelled";
      default -> throw new IllegalArgumentException("unknown Event routing for " + messageType);
    };
  }

  private static String required(JsonNode payload, String field) {
    if (!payload.hasNonNull(field) || !payload.path(field).isTextual() || payload.path(field).asText().isBlank()) {
      throw new IllegalArgumentException(field + " is required for internal command idempotency");
    }
    return payload.path(field).asText();
  }

  private static long requiredNumber(JsonNode payload, String field) {
    if (!payload.has(field) || !payload.path(field).isIntegralNumber()) {
      throw new IllegalArgumentException(field + " is required for internal command idempotency");
    }
    return payload.path(field).asLong();
  }
}
