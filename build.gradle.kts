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

// Not a dependency — an agent handed to the test JVM (see the Test task below for why). Declared
// before `dependencies` because the Kotlin DSL delegate must exist before that block references it.
val mockitoAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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

    // exchange bundle (import/export platform — streaming CSV)
    implementation(libs.commons.csv)
    implementation(libs.poi.ooxml)

    // storage bundle (files module — SeaweedFS/S3 via AWS SDK v2)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.apache.client)

    // mcp bundle (agent protocol surface — stateless streamable HTTP servlet at /mcp)
    implementation(platform(libs.mcp.bom))
    implementation(libs.mcp)

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

    // Just the jar, no transitives: handed to the test JVM as an agent, not put on the classpath.
    mockitoAgent(platform(libs.boot.bom)) // version stays the BOM's, not a second one to bump
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
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

/**
 * Mockito's inline mock maker rewrites bytecode, which needs a ByteBuddy agent inside the test JVM.
 * Left to itself it SELF-ATTACHES on first use: fine on a laptop, refused inside the CI agent
 * container, where a process may not attach to itself. It surfaces as MockitoInitializationException
 * with no failing assertion to point at, so it reads like a broken test rather than a missing JVM flag.
 * Handing the agent over at JVM start removes the attach step entirely — and self-attachment is
 * deprecated in Mockito 5.14+ anyway, so this is where it was going regardless.
 *
 * 26 test classes here use Mockito. The gateway build carries the identical block for the same reason;
 * it failed there first only because CI runs the gateway suite by default (see Jenkinsfile TEST_TASKS).
 * Reproduce the container's restriction locally with `-PblockDynamicAgents`.
 */
tasks.withType<Test> {
    useJUnitPlatform()
    // A FileCollection, resolved lazily at execution — a bare `singleFile` here would be read at
    // configuration time and cost the configuration cache.
    val agent: FileCollection = mockitoAgent
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agent.singleFile.absolutePath}")
    })
    if (providers.gradleProperty("blockDynamicAgents").isPresent) {
        jvmArgs("-XX:-EnableDynamicAgentLoading")
    }
    // The suite keeps many Spring contexts cached in one worker (each distinct @TestPropertySource or
    // webEnvironment forks another), and the default 512m heap runs out partway through — surfacing as
    // "Test process encountered an unexpected problem" or an EOFException from the worker, AFTER every
    // test has reported green. A crash that looks like a build failure but names no failing test costs
    // far more to diagnose than the memory costs to grant.
    maxHeapSize = "2g"
    // Testcontainers 2.x only reads this as an env var. With VM-based Docker (Colima, Docker
    // Desktop) Ryuk must mount the VM-internal socket, not the host-side proxy socket path.
    if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}

// Publishing the image authenticates through the Boot plugin, NOT the docker CLI. The CI agent is a JDK
// container with a Docker-in-Docker sidecar: there is a daemon on DOCKER_HOST (Testcontainers reaches it
// fine) but no `docker` binary, so the pipeline's `docker login` died with "docker: not found". It would
// not have helped either — `--publishImage` reads these credentials rather than the CLI's config.json.
// Empty when unset, so a local `bootBuildImage` without --publishImage still works.
// CI passes -PimageBase/-PimageTag so BOTH images can be built by ONE gradlew invocation. Two separate
// invocations died repeatedly: the second JVM pair started while the first image build's memory was still
// resident in the container, and the cgroup OOMKilled it (exit 137) at exactly that seam. Absent the
// properties the task keeps its default name, so a local `bootBuildImage` is unaffected.
val imageBase = providers.gradleProperty("imageBase")
val imageTag = providers.gradleProperty("imageTag")

tasks.bootBuildImage {
    if (imageBase.isPresent && imageTag.isPresent) {
        imageName.set("${imageBase.get()}/modulith:${imageTag.get()}")
    }
    // Pin the buildpack caches to STABLE volume names. Paketo does not name these after the project, it
    // names them after the IMAGE: pack-cache-<sha256(name:tag)[0..12]>.{build,launch} (Lifecycle
    // .createVolumeCache -> VolumeName.basedOn, and the tag is part of the digest input). CI tags with
    // ${GIT_COMMIT} — so every commit hashed to a brand-new pair of volumes, and that trap cut both ways:
    //   - the cache could never HIT. The restorer met an empty build cache and the analyzer an empty
    //     launch cache on every single run. The dind PVC's stated job of persisting the buildpack caches
    //     "including the JRE" was only ever true of the builder/run images; the caches never once hit.
    //   - the cache could never SHRINK. Lifecycle.close() deletes only the random pack-layers-*/pack-app-*
    //     workspace volumes. The pack-cache-* pair is deliberately kept — that is what makes it a cache.
    //     Give a cache a name that never repeats and "persistent" quietly becomes "immortal": four more
    //     volumes abandoned per build, none of them ever read again.
    // That is the disk half of the CI outage. dind's cache reached 8.9G against a PVC declaring 6Gi that
    // local-path does not enforce, the node's image filesystem hit 88%, and the kubelet's image garbage
    // collector responded by deleting smsone/modulith:dev — which is ctr-imported and runs with
    // imagePullPolicy: Never, so nothing in-cluster could re-fetch it and the platform stayed down until
    // the image was rebuilt on the Mac and re-streamed in. Note the failure was disk, NOT memory: the run
    // that proved it built no images at all and still evicted pods, at 53% of RAM.
    // Named, the volume count is bounded at four in total rather than four per build, and because the
    // caches finally hit, the Build stage also gets faster and its peak footprint smaller.
    // The gateway MUST use different names. Sharing a launch cache between two different applications is
    // exactly the corruption recorded in the Jenkinsfile from when one unqualified task name built both
    // images under a single --imageName: "caching layer ... /launch-cache/staging/...tar: no such file or
    // directory". Same volume, two sets of layers, whichever finishes second loses.
    // Deliberately NOT naming buildWorkspace: left unset its layers/app volumes are random per build AND
    // deleted in close(), so they were never the leak — naming them would only create volumes to keep.
    buildCache { volume { name.set("smsone-modulith-build") } }
    launchCache { volume { name.set("smsone-modulith-launch") } }
    docker {
        publishRegistry {
            username.set(providers.environmentVariable("GHCR_USER").getOrElse(""))
            password.set(providers.environmentVariable("GHCR_PAT").getOrElse(""))
            url.set("https://ghcr.io")
        }
    }
}
