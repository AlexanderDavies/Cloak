package com.cloak.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.cloak.server.ServerApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packagesOf = ServerApplication.class)
class ArchitectureBoundaryTest {

  @ArchTest
  static final ArchRule domainIsIsolated =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "jakarta..", "com.fasterxml..", "org.hibernate..")
          .allowEmptyShould(true)
          .because("domain must be pure Java — no Spring, JPA, Jackson, or other infra");

  @ArchTest
  static final ArchRule adaptersDoNotLeakIntoUseCases =
      noClasses()
          .that()
          .resideInAPackage("..usecase..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter..")
          .allowEmptyShould(true)
          .because("use cases depend on ports, never on adapters");
}
