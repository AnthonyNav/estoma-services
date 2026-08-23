package com.estoma.platformbff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.estoma.platformbff")
class ArchitectureTest {
  @ArchTest
  static final ArchRule domain_is_framework_and_adapter_free = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.estoma.platformbff.infrastructure..",
          "org.springframework..",
          "org.slf4j..",
          "com.fasterxml.jackson..",
          "tools.jackson..");

  @ArchTest
  static final ArchRule application_does_not_depend_on_adapters = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.application..")
      .should().dependOnClassesThat().resideInAnyPackage("com.estoma.platformbff.infrastructure..");

  @ArchTest
  static final ArchRule controllers_do_not_access_repositories = noClasses()
      .that().areAnnotatedWith(RestController.class)
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule listeners_do_not_access_repositories_directly = noClasses()
      .that().haveSimpleNameEndingWith("Listener")
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule http_contracts_do_not_reuse_messaging_contracts = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.contracts.http..")
      .should().dependOnClassesThat().resideInAnyPackage("com.estoma.platformbff.contracts.messaging..");

  @ArchTest
  static final ArchRule messaging_contracts_do_not_reuse_http_contracts = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.contracts.messaging..")
      .should().dependOnClassesThat().resideInAnyPackage("com.estoma.platformbff.contracts.http..");

  @ArchTest
  static final ArchRule repositories_stay_in_infrastructure = noClasses()
      .that().areAnnotatedWith(Repository.class)
      .should().resideOutsideOfPackage("com.estoma.platformbff.infrastructure.persistence..");
}
