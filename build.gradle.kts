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

    // observability bundle
    implementation(libs.boot.actuator)
    implementation(libs.boot.otel)
    implementation(libs.modulith.actuator)
    implementation(libs.modulith.observability)
    implementation(libs.logstash.encoder)
    implementation(libs.ulid.creator)

    developmentOnly(platform(libs.boot.bom)) // developmentOnly does not inherit implementation's platforms
    developmentOnly(libs.boot.docker.compose)

    testImplementation(libs.boot.test)
    testImplementation(libs.modulith.test)
    testImplementation(libs.archunit)
    // Later phases: data-jpa/flyway/postgresql, security/oauth2-rs, awssdk s3, data-redis/cache/caffeine,
    //               shedlock, duckdb, resilience4j, bucket4j, testcontainers-*  (all in the catalog)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
