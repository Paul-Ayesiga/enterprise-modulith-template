plugins {
    `java-library`
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
    api(platform(libs.boot.bom))

    // The core's domain is the reactive web request — ServerWebExchange + reactor. It deliberately
    // does NOT depend on spring-cloud-gateway (the runtime lives in gateway-app) nor on any platform
    // module. The GatewayCoreArchitectureTest enforces both boundaries.
    api("org.springframework:spring-web")
    api("io.projectreactor:reactor-core")

    testImplementation(libs.boot.test)
    testImplementation(libs.archunit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // Gradle 9 + JUnit 5 needs it explicit
}

tasks.withType<Test> {
    useJUnitPlatform()
}
