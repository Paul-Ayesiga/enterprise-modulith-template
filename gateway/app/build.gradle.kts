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

// Not a dependency — an agent handed to the test JVM. Declared before `dependencies` because the
// Kotlin DSL delegate must exist before that block references it.
val mockitoAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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
    // Traffic management (Phase 3): reactive Valkey for the rate limiter, Resilience4j for circuit breaking.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    // Phase 3c — response caching (SCG LocalResponseCache is backed by Caffeine) and load-balancing
    // (resolve lb://service across its instances).
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    // Phase 4 — observability: Prometheus registry for the gateway's metrics (scraped at /actuator/prometheus).
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Pushes METRICS (incl. resilience4j breaker state) over OTLP to the LGTM stack — the breaker-open
    // alert reads them; without this only the unscraped /prometheus endpoint had them. Deliberately the
    // metrics-only registry, NOT the full OTel starter: the app already has its own tracer, and a second
    // tracing SDK forks the trace ids the edge propagates (TracingTest guards this).
    implementation("io.micrometer:micrometer-registry-otlp")

    testImplementation(libs.boot.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-oauth2-jose") // brings Nimbus JOSE — mint JWTs + JWKS in SecurityTest
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // Gradle 9 + JUnit 5 needs it explicit

    // Just the jar, no transitives: this is not a dependency, it is an agent handed to the test JVM.
    mockitoAgent(platform(libs.boot.bom)) // so the version stays the BOM's, not a second one to bump
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

/**
 * Mockito's inline mock maker rewrites bytecode, which needs a ByteBuddy agent inside the test JVM.
 * Left to itself it SELF-ATTACHES on first use — which succeeds on a developer laptop and fails inside
 * the CI agent container, where attaching to your own process is not permitted. It surfaces as
 * MockitoInitializationException with no failing assertion to point at, so it reads like a broken test
 * rather than a missing JVM flag; `QuotaFilterChainTest` mocks the concrete ReactiveStringRedisTemplate,
 * so it is the whole gateway suite's exposure to this.
 *
 * Passing the agent at JVM start removes the attach step entirely, rather than trying to re-permit it
 * (-XX:+EnableDynamicAgentLoading would only paper over it, and self-attachment is deprecated in
 * Mockito 5.14+ and slated to stop working outright in a future JDK).
 *
 * Reproduce the CI restriction locally with `-PblockDynamicAgents` (below): it denies self-attachment
 * exactly as the container does, so the guard can be re-proved without waiting on a pipeline run.
 */
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // A FileCollection, resolved lazily at execution: keeps the configuration cache usable, which a
    // bare `mockitoAgent.singleFile` at configuration time would not.
    val agent: FileCollection = mockitoAgent
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agent.singleFile.absolutePath}")
    })
    // Denies self-attachment the way the CI container does, so the line above can be proved to be
    // load-bearing without pushing a commit to find out.
    if (providers.gradleProperty("blockDynamicAgents").isPresent) {
        jvmArgs("-XX:-EnableDynamicAgentLoading")
    }
    // Testcontainers 2.x + VM-based Docker (Colima, Docker Desktop): Ryuk must mount the VM-internal
    // socket, not the host-side proxy path. Mirrors the modulith build.
    if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}

// GHCR credentials for --publishImage. Mirrors the modulith build; see the comment there for why the
// pipeline cannot rely on `docker login`.
// Named from the same -PimageBase/-PimageTag as the modulith, so one gradlew run produces both images
// under their own names. See the comment in the root build for why two runs could not be used.
val imageBase = providers.gradleProperty("imageBase")
val imageTag = providers.gradleProperty("imageTag")

tasks.bootBuildImage {
    if (imageBase.isPresent && imageTag.isPresent) {
        imageName.set("${imageBase.get()}/gateway:${imageTag.get()}")
    }
    // Stable buildpack cache volumes; see the root build for the full account. In short: Paketo names
    // these volumes after the IMAGE, not the project — pack-cache-<sha256(name:tag)[0..12]>.{build,launch}
    // — and CI tags with ${GIT_COMMIT}, so before this the cache could neither hit (a fresh empty pair
    // every commit) nor shrink (nothing deletes a pack-cache-* volume, by design). Each build simply
    // abandoned another pair, until dind filled the node's disk and the kubelet's image GC started
    // deleting the platform's own imagePullPolicy: Never images.
    // These names MUST NOT match the modulith's. The two are different applications with different
    // layers, and one shared launch cache is the exact corruption the Jenkinsfile records from the build
    // where an unqualified task name produced both images under one --imageName. gradle.properties sets
    // org.gradle.parallel=true, so absent the CI stage's --no-parallel these two tasks can also run at the
    // same time — distinct names are what makes that safe rather than a race.
    buildCache { volume { name.set("smsone-gateway-build") } }
    launchCache { volume { name.set("smsone-gateway-launch") } }
    docker {
        publishRegistry {
            username.set(providers.environmentVariable("GHCR_USER").getOrElse(""))
            password.set(providers.environmentVariable("GHCR_PAT").getOrElse(""))
            url.set("https://ghcr.io")
        }
    }
}
