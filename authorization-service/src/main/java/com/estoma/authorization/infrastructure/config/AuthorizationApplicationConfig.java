package com.estoma.authorization.infrastructure.config;

import com.estoma.authorization.application.authorization.AuthorityEvaluator;
import com.estoma.authorization.application.command.AuthorizationCommandDispatcher;
import com.estoma.authorization.application.command.AuthorizationCommandProcessor;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.AuthorizationSnapshotRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.application.port.StoredApplicationResultDecoder;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.projection.PracticesProvisioningService;
import com.estoma.authorization.application.projection.ProjectionEventProcessor;
import com.estoma.authorization.application.query.AuthorizationSnapshotQueryService;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResultHandler;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class AuthorizationApplicationConfig {

  @Bean
  Clock authorizationClock() {
    return Clock.systemUTC();
  }

  @Bean
  AuthorityEvaluator authorityEvaluator(
      AccountStatusProjectionRepository accounts,
      PersonStatusProjectionRepository persons,
      AccountRoleRepository accountRoles,
      RoleCatalogRepository roles,
      SystemCatalogRepository systems,
      ProcessedMessageRepository inbox,
      ProjectionRecoveryJobRepository recoveryJobs) {
    return new AuthorityEvaluator(
        accounts, persons, accountRoles, roles, systems, inbox, recoveryJobs);
  }

  @Bean
  PracticesProvisioningService practicesProvisioningService(
      AcademicEligibilityProjectionRepository eligibility,
      AccountStatusProjectionRepository accounts,
      AccountRoleRepository accountRoles,
      RoleCatalogRepository roles,
      SystemCatalogRepository systems,
      SystemAccessRepository accesses,
      ProcessedMessageRepository inbox,
      ProjectionRecoveryJobRepository recoveryJobs,
      OutboxRepository outbox,
      AuthorizationAuditRepository audit) {
    return new PracticesProvisioningService(
        eligibility,
        accounts,
        accountRoles,
        roles,
        systems,
        accesses,
        inbox,
        recoveryJobs,
        outbox,
        audit);
  }

  @Bean
  ProjectionRecoveryCoordinator projectionRecoveryCoordinator(
      TransactionRunner transactions,
      ProjectionRecoveryJobRepository jobs,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      OutboxRepository outbox,
      Clock clock) {
    return new ProjectionRecoveryCoordinator(
        transactions, jobs, persons, accounts, eligibility, outbox, clock);
  }

  @Bean
  ProjectionRecoveryResultHandler projectionRecoveryResultHandler(
      TransactionRunner transactions,
      ProjectionRecoveryJobRepository jobs,
      ProcessedMessageRepository inbox,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      ProjectionRecoveryCoordinator coordinator,
      PracticesProvisioningService provisioning,
      Clock clock) {
    return new ProjectionRecoveryResultHandler(
        transactions,
        jobs,
        inbox,
        persons,
        accounts,
        eligibility,
        coordinator,
        provisioning,
        clock);
  }

  @Bean
  AuthorizationSnapshotQueryService authorizationSnapshotQueryService(
      AuthorizationSnapshotRepository snapshots, Clock clock) {
    return new AuthorizationSnapshotQueryService(snapshots, clock);
  }

  @Bean
  AuthorizationCommandDispatcher authorizationCommandDispatcher(
      TransactionRunner transactions,
      AccountRoleRepository accountRoles,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      AuthorizationAuditRepository audit,
      CommandIdempotencyRepository idempotency,
      OutboxRepository outbox,
      PersonStatusProjectionRepository persons,
      ProcessedMessageRepository inbox,
      RoleCatalogRepository roles,
      SystemAccessRepository accesses,
      SystemCatalogRepository systems,
      AuthorityEvaluator authority,
      PracticesProvisioningService provisioning,
      Clock clock) {
    return new AuthorizationCommandDispatcher(
        transactions,
        accountRoles,
        accounts,
        eligibility,
        audit,
        idempotency,
        outbox,
        persons,
        inbox,
        roles,
        accesses,
        systems,
        authority,
        provisioning,
        clock);
  }

  @Bean
  @Primary
  CommandDispatcher authorizationCommandProcessor(
      TransactionRunner transactions,
      AuthorizationCommandDispatcher delegate,
      ProcessedMessageRepository inbox,
      CommandIdempotencyRepository idempotency,
      StoredApplicationResultDecoder resultDecoder,
      OutboxRepository outbox,
      AuthorizationAuditRepository audit,
      Clock clock) {
    return new AuthorizationCommandProcessor(
        transactions, delegate, inbox, idempotency, resultDecoder, outbox, audit, clock);
  }

  @Bean
  ProjectionEventProcessor projectionEventProcessor(
      TransactionRunner transactions,
      ProcessedMessageRepository inbox,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      PracticesProvisioningService provisioning,
      AuthorizationAuditRepository audit,
      Clock clock) {
    return new ProjectionEventProcessor(
        transactions, inbox, persons, accounts, eligibility, provisioning, audit, clock);
  }
}
