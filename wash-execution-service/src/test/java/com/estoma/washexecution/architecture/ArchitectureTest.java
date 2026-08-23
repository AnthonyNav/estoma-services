package com.estoma.washexecution.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.estoma.washexecution")
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
  static final ArchRule application_does_not_depend_on_infrastructure =
      noClasses()
          .that().resideInAnyPackage("com.estoma.washexecution.application..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "com.estoma.washexecution.infrastructure..",
              "org.springframework.jdbc..",
              "java.sql..")
          .because("application depends on output ports, never concrete JDBC adapters");

  @ArchTest
  static final ArchRule application_is_transport_neutral =
      noClasses()
          .that().resideInAnyPackage("com.estoma.washexecution.application..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "org.springframework.amqp..",
              "com.rabbitmq..")
          .because("RabbitMQ belongs to infrastructure and is introduced in PR3");

  @ArchTest
  static final ArchRule jdbc_implementations_live_in_persistence_infrastructure =
      classes()
          .that().haveSimpleNameStartingWith("Jdbc")
          .should().resideInAPackage("com.estoma.washexecution.infrastructure.persistence.jdbc..")
          .because("JDBC is an outbound adapter detail");
}
