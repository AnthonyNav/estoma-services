package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.command.AdministrativeCommand;
import com.estoma.washadministration.application.command.CommandMetadata;
import com.estoma.washadministration.application.command.CommandRequest;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.command.ActivateCabinCommand;
import com.estoma.washadministration.contracts.command.ActivateServiceCalendarCommand;
import com.estoma.washadministration.contracts.command.ActivateTankCommand;
import com.estoma.washadministration.contracts.command.CancelAdministrativeClosureCommand;
import com.estoma.washadministration.contracts.command.ConfigureServiceScheduleCommand;
import com.estoma.washadministration.contracts.command.CreateServiceCalendarCommand;
import com.estoma.washadministration.contracts.command.DeactivateCabinCommand;
import com.estoma.washadministration.contracts.command.DeactivateServiceCalendarCommand;
import com.estoma.washadministration.contracts.command.DeactivateServiceScheduleCommand;
import com.estoma.washadministration.contracts.command.DeactivateTankCommand;
import com.estoma.washadministration.contracts.command.RegisterCabinCommand;
import com.estoma.washadministration.contracts.command.RegisterTankCommand;
import com.estoma.washadministration.contracts.command.RetireCabinCommand;
import com.estoma.washadministration.contracts.command.RetireTankCommand;
import com.estoma.washadministration.contracts.command.ScheduleAdministrativeClosureCommand;
import com.estoma.washadministration.contracts.command.UpdateCabinCommand;
import com.estoma.washadministration.contracts.command.UpdateServiceCalendarCommand;
import com.estoma.washadministration.contracts.command.UpdateServiceScheduleCommand;
import com.estoma.washadministration.contracts.command.UpdateTankCommand;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CommandMessageMapper {

  private final EnvelopeJsonCodec codec;

  public CommandMessageMapper(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  public CommandRequest map(MessageEnvelope envelope) {
    try {
      return new CommandRequest(metadata(envelope), payload(envelope));
    } catch (InvalidMessageException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidMessageException("INVALID_COMMAND_PAYLOAD", exception);
    }
  }

  private CommandMetadata metadata(MessageEnvelope envelope) {
    Actor actor = envelope.actor();
    ActorContext context =
        "ACCOUNT".equals(actor.type())
            ? ActorContext.account(actor.accountId())
            : ActorContext.system(actor.service());
    return new CommandMetadata(
        envelope.messageId(),
        envelope.messageType(),
        envelope.messageVersion(),
        envelope.producer(),
        envelope.occurredAt(),
        envelope.correlationId(),
        envelope.causationId(),
        envelope.operationId(),
        envelope.idempotencyKey(),
        envelope.aggregateId(),
        envelope.aggregateVersion(),
        context);
  }

  private AdministrativeCommand payload(MessageEnvelope envelope) {
    return switch (envelope.messageType()) {
      case "CreateServiceCalendar" -> {
        CreateServiceCalendarCommand value = codec.convertPayload(envelope, CreateServiceCalendarCommand.class);
        yield new AdministrativeCommand.CreateServiceCalendar(
            value.calendarId(), value.code(), value.name(), value.timezone(), value.effectiveFrom(), value.effectiveUntil());
      }
      case "UpdateServiceCalendar" -> {
        UpdateServiceCalendarCommand value = codec.convertPayload(envelope, UpdateServiceCalendarCommand.class);
        yield new AdministrativeCommand.UpdateServiceCalendar(
            value.calendarId(), value.name(), value.timezone(), value.effectiveFrom(), value.effectiveUntil(), value.expectedVersion());
      }
      case "ActivateServiceCalendar" -> {
        ActivateServiceCalendarCommand value = codec.convertPayload(envelope, ActivateServiceCalendarCommand.class);
        yield new AdministrativeCommand.ActivateServiceCalendar(value.calendarId(), value.expectedVersion());
      }
      case "DeactivateServiceCalendar" -> {
        DeactivateServiceCalendarCommand value = codec.convertPayload(envelope, DeactivateServiceCalendarCommand.class);
        yield new AdministrativeCommand.DeactivateServiceCalendar(value.calendarId(), value.reason(), value.expectedVersion());
      }
      case "ConfigureServiceSchedule" -> {
        ConfigureServiceScheduleCommand value = codec.convertPayload(envelope, ConfigureServiceScheduleCommand.class);
        yield new AdministrativeCommand.ConfigureServiceSchedule(
            value.scheduleId(), value.calendarId(), value.dayOfWeek(), value.openingTime(), value.closingTime(), value.slotDurationMinutes());
      }
      case "UpdateServiceSchedule" -> {
        UpdateServiceScheduleCommand value = codec.convertPayload(envelope, UpdateServiceScheduleCommand.class);
        yield new AdministrativeCommand.UpdateServiceSchedule(
            value.scheduleId(), value.dayOfWeek(), value.openingTime(), value.closingTime(), value.slotDurationMinutes(), value.expectedVersion());
      }
      case "DeactivateServiceSchedule" -> {
        DeactivateServiceScheduleCommand value = codec.convertPayload(envelope, DeactivateServiceScheduleCommand.class);
        yield new AdministrativeCommand.DeactivateServiceSchedule(value.scheduleId(), value.reason(), value.expectedVersion());
      }
      case "ScheduleAdministrativeClosure" -> {
        ScheduleAdministrativeClosureCommand value = codec.convertPayload(envelope, ScheduleAdministrativeClosureCommand.class);
        yield new AdministrativeCommand.ScheduleAdministrativeClosure(
            value.closureId(), value.calendarId(), AdministrativeClosureScope.valueOf(value.scope()), value.cabinId(), value.tankId(), value.startsAt(), value.endsAt(), value.reason());
      }
      case "CancelAdministrativeClosure" -> {
        CancelAdministrativeClosureCommand value = codec.convertPayload(envelope, CancelAdministrativeClosureCommand.class);
        yield new AdministrativeCommand.CancelAdministrativeClosure(value.closureId(), value.reason(), value.expectedVersion());
      }
      case "RegisterCabin" -> {
        RegisterCabinCommand value = codec.convertPayload(envelope, RegisterCabinCommand.class);
        yield new AdministrativeCommand.RegisterCabin(value.cabinId(), value.code(), value.name(), CabinType.valueOf(value.cabinType()));
      }
      case "UpdateCabin" -> {
        UpdateCabinCommand value = codec.convertPayload(envelope, UpdateCabinCommand.class);
        yield new AdministrativeCommand.UpdateCabin(value.cabinId(), value.name(), CabinType.valueOf(value.cabinType()), value.expectedVersion());
      }
      case "ActivateCabin" -> {
        ActivateCabinCommand value = codec.convertPayload(envelope, ActivateCabinCommand.class);
        yield new AdministrativeCommand.ActivateCabin(value.cabinId(), value.expectedVersion());
      }
      case "DeactivateCabin" -> {
        DeactivateCabinCommand value = codec.convertPayload(envelope, DeactivateCabinCommand.class);
        yield new AdministrativeCommand.DeactivateCabin(value.cabinId(), value.reason(), value.expectedVersion());
      }
      case "RetireCabin" -> {
        RetireCabinCommand value = codec.convertPayload(envelope, RetireCabinCommand.class);
        yield new AdministrativeCommand.RetireCabin(value.cabinId(), value.reason(), value.expectedVersion());
      }
      case "RegisterTank" -> {
        RegisterTankCommand value = codec.convertPayload(envelope, RegisterTankCommand.class);
        yield new AdministrativeCommand.RegisterTank(value.tankId(), value.cabinId(), value.code(), value.name(), value.configuredCapacity());
      }
      case "UpdateTank" -> {
        UpdateTankCommand value = codec.convertPayload(envelope, UpdateTankCommand.class);
        yield new AdministrativeCommand.UpdateTank(value.tankId(), value.name(), value.configuredCapacity(), value.expectedVersion());
      }
      case "ActivateTank" -> {
        ActivateTankCommand value = codec.convertPayload(envelope, ActivateTankCommand.class);
        yield new AdministrativeCommand.ActivateTank(value.tankId(), value.expectedVersion());
      }
      case "DeactivateTank" -> {
        DeactivateTankCommand value = codec.convertPayload(envelope, DeactivateTankCommand.class);
        yield new AdministrativeCommand.DeactivateTank(value.tankId(), value.reason(), value.expectedVersion());
      }
      case "RetireTank" -> {
        RetireTankCommand value = codec.convertPayload(envelope, RetireTankCommand.class);
        yield new AdministrativeCommand.RetireTank(value.tankId(), value.reason(), value.expectedVersion());
      }
      default -> throw new InvalidMessageException("COMMAND_TYPE_NOT_ALLOWED");
    };
  }
}
