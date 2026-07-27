plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "ug.co.smsone"
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
    implementation(platform(libs.spring.modulith.bom))

    // web-api bundle
    implementation(libs.boot.web)
    implementation(libs.boot.validation)
    implementation(libs.springdoc)
    implementation(libs.modulith.core)
    implementation(libs.modulith.jdbc)

    // observability bundle
    implementation(libs.boot.actuator)
    implementation(libs.boot.otel)
    implementation(libs.modulith.actuator)
    implementation(libs.modulith.observability)
    implementation(libs.logstash.encoder)
    implementation(libs.micrometer.java21)
    implementation(libs.ulid.creator)

    // security bundle
    implementation(libs.boot.security)
    implementation(libs.boot.oauth2.rs)

    // analytics bundle (embedded OLAP — in-process, not a container)
    implementation(libs.duckdb)

    // resilience bundle (verified: -spring-boot4 artifact; -spring-boot3 fail-fasts on Boot 4)
    implementation(libs.resilience4j)
    implementation(libs.aspectjweaver)

    // scheduling bundle (ShedLock — one job execution across all instances)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)

    // caching bundle (Caffeine L1 + Valkey L2)
    implementation(libs.boot.cache)
    implementation(libs.boot.data.redis)
    implementation(libs.caffeine)

    // notification bundle (email via SMTP; Mailpit in dev — event-driven consumer)
    implementation(libs.boot.mail)

    // rate limiting bundle (Bucket4j token bucket; distributed over Valkey via Lettuce)
    implementation(libs.bucket4j)
    implementation(libs.bucket4j.lettuce)

    // storage bundle (files module — SeaweedFS/S3 via AWS SDK v2)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.apache.client)

    // persistence bundle
    implementation(libs.boot.data.jpa)
    implementation(libs.boot.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    developmentOnly(platform(libs.boot.bom)) // developmentOnly does not inherit implementation's platforms
    developmentOnly(libs.boot.docker.compose)

    testImplementation(libs.boot.test)
    testImplementation(libs.boot.webmvc.test)
    testImplementation(libs.boot.resttestclient)
    testImplementation(libs.boot.restclient)
    testImplementation(libs.security.test)
    testImplementation(libs.boot.testcontainers)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.modulith.test)
    testImplementation(libs.archunit)
    // Later phases: data-jpa/flyway/postgresql, security/oauth2-rs, awssdk s3, data-redis/cache/caffeine,
    //               shedlock, duckdb, resilience4j, bucket4j, testcontainers-*  (all in the catalog)
}

tasks.register<Test>("exportModulithDocs") {
    description = "Writes Modulith C4 diagrams + module canvases to docs/modulith/"
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("docs-export") }
    outputs.upToDateWhen { false }
}

// Re-runs only the tagged export test; the spec also refreshes on every normal build.
tasks.register<Test>("exportOpenApi") {
    description = "Boots the app (test profile) and writes docs/openapi/openapi.{yaml,json}"
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("openapi-export") }
    outputs.upToDateWhen { false }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers 2.x only reads this as an env var. With VM-based Docker (Colima, Docker
    // Desktop) Ryuk must mount the VM-internal socket, not the host-side proxy socket path.
    if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}
