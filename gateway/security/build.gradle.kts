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
    implementation(platform(libs.spring.cloud.bom))

    implementation(project(":gateway:core"))
    // Reactive Spring Security + OAuth2 resource server (JWT validated against a JWKS — no platform call).
    implementation(libs.boot.security)
    implementation(libs.boot.oauth2.rs)
    // The runtime types the coarse-authZ GlobalFilter binds to (GlobalFilter, ServerWebExchangeUtils, Route).
    implementation(libs.gateway.webflux)

    testImplementation(libs.boot.test)
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
