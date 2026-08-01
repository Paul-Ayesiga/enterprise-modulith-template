plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "ug.co.smsone.gateway"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.boot.bom))
    implementation(platform(libs.spring.cloud.bom))

    // The runtime-agnostic gateway core (models + ports); the app is the SCG runtime that executes it.
    implementation(project(":gateway:core"))
    // Edge security (JWT/OIDC, coarse authZ, CORS) — component-scanned into the app context.
    implementation(project(":gateway:security"))
    // Platform adapters (API-key introspection) — the seam to this template's modulith.
    implementation(project(":gateway:platform-adapter"))
    // Reactive Spring Cloud Gateway (WebFlux) — the edge runtime.
    implementation(libs.gateway.webflux)
    // Health + metrics for the gateway itself.
    implementation(libs.boot.actuator)

    testImplementation(libs.boot.test)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-oauth2-jose") // brings Nimbus JOSE — mint JWTs + JWKS in SecurityTest
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // Gradle 9 + JUnit 5 needs it explicit
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
