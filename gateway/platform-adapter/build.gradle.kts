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
    implementation(platform(libs.boot.bom))

    // Implements the core ports; talks to the platform over reactive HTTP (WebClient).
    implementation(project(":gateway:core"))
    implementation("org.springframework:spring-webflux")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // Best-effort audit publishing logs its own failures (to the gateway.error stream).
    implementation("org.slf4j:slf4j-api")

    testImplementation(libs.boot.test)
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
