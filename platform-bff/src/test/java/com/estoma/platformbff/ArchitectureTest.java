package com.estoma.platformbff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Repository;

@AnalyzeClasses(packages = "com.estoma.platformbff")
class ArchitectureTest {
  @ArchTest
  static final ArchRule domain_does_not_depend_on_spring = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.domain..")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.slf4j..");

  @ArchTest
  static final ArchRule application_does_not_depend_on_adapters = noClasses()
      .that().resideInAnyPackage("com.estoma.platformbff.application..")
      .should().dependOnClassesThat().resideInAnyPackage("com.estoma.platformbff.infrastructure..");

  @ArchTest
  static final ArchRule repositories_stay_in_infrastructure = noClasses()
      .that().areAnnotatedWith(Repository.class)
      .should().resideOutsideOfPackage("com.estoma.platformbff.infrastructure.persistence..");
}
