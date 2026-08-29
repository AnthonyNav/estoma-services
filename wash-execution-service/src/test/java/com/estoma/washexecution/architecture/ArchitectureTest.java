package com.estoma.washexecution.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.estoma.washexecution",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_is_framework_and_adapter_free =
      noClasses()
          .that().resideInAnyPackage("com.estoma.washexecution.domain..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.estoma.washexecution.application..",
              "com.estoma.washexecution.infrastructure..",
              "com.estoma.washexecution.contracts..",
              "org.springframework..",
              "org.postgresql..",
              "java.sql..",
              "tools.jackson..",
              "com.fasterxml.jackson..")
          .because("domain must remain framework-free and independent of outer hexagonal layers");

  @ArchTest
  static final ArchRule application_is_framework_and_adapter_free =
      noClasses()
          .that().resideInAnyPackage("com.estoma.washexecution.application..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.estoma.washexecution.infrastructure..",
              "org.springframework..",
              "org.postgresql..",
              "java.sql..",
              "org.springframework.amqp..",
              "com.rabbitmq..",
              "tools.jackson..",
              "com.fasterxml.jackson..")
          .because("application owns use cases and ports, not Spring, JDBC, RabbitMQ, or JSON adapters");

  @ArchTest
  static final ArchRule jdbc_implementations_live_in_persistence_infrastructure =
      classes()
          .that().haveSimpleNameStartingWith("Jdbc")
          .should().resideInAPackage("com.estoma.washexecution.infrastructure.persistence.jdbc..")
          .because("JDBC is an outbound adapter detail");

  @ArchTest
  static final ArchRule schedulers_live_in_scheduling_infrastructure =
      classes()
          .that().haveSimpleNameEndingWith("Scheduler")
          .should().resideInAPackage("com.estoma.washexecution.infrastructure.scheduling..")
          .because("schedulers are inbound infrastructure adapters");
}
