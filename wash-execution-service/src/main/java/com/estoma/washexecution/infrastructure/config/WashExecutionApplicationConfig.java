package com.estoma.washexecution.infrastructure.config;

import com.estoma.washexecution.application.authorization.AuthorizationEvaluator;
import com.estoma.washexecution.application.authorization.ProjectionReadinessGuard;
import com.estoma.washexecution.application.cleaning.CleaningPeriodCompletionService;
import com.estoma.washexecution.application.command.WashExecutionCommandService;
import com.estoma.washexecution.application.port.AffectedExecutionRepository;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.CommandIdempotencyRepository;
import com.estoma.washexecution.application.port.EntryValidationRepository;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.application.port.WashExecutionIntegrationQueryRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.application.query.WashExecutionIntegrationQueryService;
import com.estoma.washexecution.application.resource.ResourceAllocator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WashExecutionApplicationConfig {

  @Bean
  Clock washExecutionClock() {
    return Clock.systemUTC();
  }

  @Bean
  ProjectionReadinessGuard projectionReadinessGuard(ProjectionRecoveryRepository recovery) {
    return new ProjectionReadinessGuard(recovery);
  }

  @Bean
  AuthorizationEvaluator authorizationEvaluator(
      ProjectionRepositories.AuthorizationProjectionRepository projections,
      ProjectionReadinessGuard readiness) {
    return new AuthorizationEvaluator(projections, readiness);
  }

  @Bean
  ResourceAllocator resourceAllocator(
      ProjectionRepositories.ResourceProjectionRepository resources,
      ProjectionRepositories.AdministrativeClosureRepository closures,
      ResourceAssignmentRepository assignments,
      ResourceUnavailabilityRepository unavailabilities,
      CleaningPeriodRepository cleanings,
      ResourceConcurrencyRepository locks,
      ProjectionReadinessGuard readiness) {
    return new ResourceAllocator(
        resources, closures, assignments, unavailabilities, cleanings, locks, readiness);
  }

  @Bean
  WashExecutionCommandService washExecutionCommandService(
      WashExecutionRepository executions,
      EntryValidationRepository validations,
      ResourceAssignmentRepository assignments,
      ResourceUnavailabilityRepository unavailabilities,
      AffectedExecutionRepository affectedExecutions,
      CleaningPeriodRepository cleanings,
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionRepositories.ResourceProjectionRepository resources,
      ResourceConcurrencyRepository locks,
      ResourceAllocator allocator,
      AuthorizationEvaluator authorization,
      ProjectionReadinessGuard readiness,
      OutboxRepository outbox,
      CommandIdempotencyRepository idempotency,
      JsonCodec json,
      Clock clock,
      @Value("${estoma.entry.unclassified-rejection-enabled:false}")
          boolean unclassifiedEntryRejectionEnabled,
      @Value("${estoma.completion.direct-supervisor-enabled:false}")
          boolean directSupervisorCompletionEnabled) {
    return new WashExecutionCommandService(
        executions, validations, assignments, unavailabilities, affectedExecutions, cleanings,
        appointments, slots, resources, locks, allocator, authorization, readiness, outbox,
        idempotency, json, clock, unclassifiedEntryRejectionEnabled,
        directSupervisorCompletionEnabled);
  }

  @Bean
  CleaningPeriodCompletionService cleaningPeriodCompletionService(
      CleaningPeriodRepository periods,
      WashExecutionRepository executions,
      OutboxRepository outbox,
      JsonCodec json,
      Clock clock) {
    return new CleaningPeriodCompletionService(periods, executions, outbox, json, clock);
  }

  @Bean
  WashExecutionIntegrationQueryService washExecutionIntegrationQueryService(
      WashExecutionIntegrationQueryRepository repository,
      WashExecutionRepository executions,
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionReadinessGuard readiness,
      ResourceAllocator allocator,
      OutboxRepository outbox,
      JsonCodec json,
      Clock clock) {
    return new WashExecutionIntegrationQueryService(
        repository, executions, appointments, slots, readiness, allocator, outbox, json, clock);
  }
}
