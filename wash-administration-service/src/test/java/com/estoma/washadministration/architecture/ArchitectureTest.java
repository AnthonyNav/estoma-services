package com.estoma.washadministration.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.estoma.washadministration")
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_does_not_depend_on_framework_or_adapters =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.washadministration.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.postgresql..",
              "java.sql..",
              "com.estoma.washadministration.infrastructure..",
              "com.estoma.washadministration.contracts..")
          .because("the domain is pure and independent of adapters and wire contracts");

  @ArchTest
  static final ArchRule application_does_not_depend_on_infrastructure =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.washadministration.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.estoma.washadministration.infrastructure..");

  @ArchTest
  static final ArchRule application_does_not_depend_on_json_framework_types =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.washadministration.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
          .because("wire/JSON mapping belongs to infrastructure/contracts in PR3");

  @ArchTest
  static final ArchRule contracts_do_not_depend_on_business_implementation =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.washadministration.contracts..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.estoma.washadministration.domain..",
              "com.estoma.washadministration.application..");
}
