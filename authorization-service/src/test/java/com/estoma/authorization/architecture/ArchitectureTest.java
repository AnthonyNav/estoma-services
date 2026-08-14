package com.estoma.authorization.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.estoma.authorization")
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_is_framework_free =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.authorization.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.postgresql..",
              "java.sql..",
              "com.estoma.authorization.application..",
              "com.estoma.authorization.infrastructure..",
              "com.estoma.authorization.contracts..")
          .because("the domain must remain independent of adapters and wire contracts");

  @ArchTest
  static final ArchRule application_does_not_depend_on_infrastructure =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.authorization.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.estoma.authorization.infrastructure..");

  @ArchTest
  static final ArchRule contracts_do_not_depend_on_business_code =
      noClasses()
          .that()
          .resideInAnyPackage("com.estoma.authorization.contracts..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.estoma.authorization.domain..", "com.estoma.authorization.application..");
}
