package com.estoma.appointments.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Repository;

@AnalyzeClasses(packages = "com.estoma.appointments")
class HexagonalArchitectureTest {
  @ArchTest static final ArchRule domain_is_framework_free = noClasses()
      .that().resideInAnyPackage("..domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..",
          "org.springframework.amqp..",
          "org.flywaydb..",
          "org.postgresql..",
          "com.fasterxml.jackson..",
          "com.estoma.appointments.infrastructure..",
          "com.estoma.appointments.contracts..");

  @ArchTest static final ArchRule application_does_not_depend_on_adapters = noClasses()
      .that().resideInAnyPackage("..application..")
      .should().dependOnClassesThat().resideInAnyPackage("com.estoma.appointments.infrastructure..");

  @ArchTest static final ArchRule contracts_do_not_depend_on_business_or_adapters = noClasses()
      .that().resideInAnyPackage("..contracts..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.estoma.appointments.domain..",
          "com.estoma.appointments.application.usecase..",
          "com.estoma.appointments.infrastructure..");

  @ArchTest static final ArchRule repositories_are_in_infrastructure = noClasses()
      .that().areAnnotatedWith(Repository.class)
      .should().resideOutsideOfPackage("com.estoma.appointments.infrastructure..");
}
