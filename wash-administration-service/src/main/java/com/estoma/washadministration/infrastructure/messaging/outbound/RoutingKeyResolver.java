package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.contracts.MessagingNames;
import org.springframework.stereotype.Component;

@Component
public final class RoutingKeyResolver {

  public Destination resolve(MessageKind kind, String messageType, String logicalDestination) {
    return switch (kind) {
      case RESULT -> new Destination(MessagingNames.RESULTS_EXCHANGE, requiredRequester(logicalDestination));
      case QUERY -> new Destination(MessagingNames.QUERIES_EXCHANGE, requiredQueryOwner(logicalDestination));
      case EVENT -> new Destination(MessagingNames.EVENTS_EXCHANGE, eventRoutingKey(messageType));
      case COMMAND -> throw new IllegalArgumentException("wash-administration does not publish Commands");
    };
  }

  private static String requiredRequester(String value) {
    if (value == null
        || (!value.equals("bff")
            && !value.equals("appointments")
            && !value.equals("wash-execution")
            && !value.equals("academic")
            && !value.equals(MessagingNames.LOGICAL_COMPONENT_ID))) {
      throw new IllegalArgumentException("unsupported Result requester: " + value);
    }
    return value;
  }

  private static String requiredQueryOwner(String value) {
    if (value == null || !value.equals("authorization")) {
      throw new IllegalArgumentException("unsupported Query owner: " + value);
    }
    return value;
  }

  public static String eventRoutingKey(String messageType) {
    return switch (messageType) {
      case "ServiceCalendarCreated" -> "wash-administration.service-calendar.created";
      case "ServiceCalendarUpdated" -> "wash-administration.service-calendar.updated";
      case "ServiceCalendarActivated" -> "wash-administration.service-calendar.activated";
      case "ServiceCalendarDeactivated" -> "wash-administration.service-calendar.deactivated";
      case "ServiceScheduleConfigured" -> "wash-administration.service-schedule.configured";
      case "ServiceScheduleUpdated" -> "wash-administration.service-schedule.updated";
      case "ServiceScheduleDeactivated" -> "wash-administration.service-schedule.deactivated";
      case "AdministrativeClosureScheduled" -> "wash-administration.administrative-closure.scheduled";
      case "AdministrativeClosureCancelled" -> "wash-administration.administrative-closure.cancelled";
      case "CabinRegistered" -> "wash-administration.cabin.registered";
      case "CabinUpdated" -> "wash-administration.cabin.updated";
      case "CabinActivated" -> "wash-administration.cabin.activated";
      case "CabinDeactivated" -> "wash-administration.cabin.deactivated";
      case "CabinRetired" -> "wash-administration.cabin.retired";
      case "TankRegistered" -> "wash-administration.tank.registered";
      case "TankUpdated" -> "wash-administration.tank.updated";
      case "TankActivated" -> "wash-administration.tank.activated";
      case "TankDeactivated" -> "wash-administration.tank.deactivated";
      case "TankRetired" -> "wash-administration.tank.retired";
      case "ResourcePriorityConfigured" -> "wash-administration.resource-priority.configured";
      default -> throw new IllegalArgumentException("unsupported Event type: " + messageType);
    };
  }

  public record Destination(String exchange, String routingKey) {}
}
