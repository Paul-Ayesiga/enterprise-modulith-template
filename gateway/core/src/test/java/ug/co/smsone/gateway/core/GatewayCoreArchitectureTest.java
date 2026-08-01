package ug.co.smsone.gateway.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * The core is runtime-agnostic AND platform-agnostic: it must not depend on the Spring Cloud Gateway
 * runtime, nor on any platform (modulith) package. That boundary is what lets the same core front a
 * different platform — or be extracted / open-sourced — without a rewrite (ADR 0007).
 */
class GatewayCoreArchitectureTest {

    private static final JavaClasses CORE =
            new ClassFileImporter().importPackages("ug.co.smsone.gateway.core");

    @Test
    void coreDependsOnNoGatewayRuntime() {
        noClasses().should().dependOnClassesThat()
                .resideInAPackage("org.springframework.cloud.gateway..")
                .check(CORE);
    }

    @Test
    void coreDependsOnNoPlatformModule() {
        noClasses().should().dependOnClassesThat(
                        resideInAPackage("ug.co.smsone..")
                                .and(resideOutsideOfPackage("ug.co.smsone.gateway..")))
                .check(CORE);
    }
}
