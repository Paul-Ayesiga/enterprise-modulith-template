plugins {
    // Auto-provisions the Java 21 toolchain when only another JDK is installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "enterprise-modulith-template"

// The gateway is a separate reactive deployable (ADR 0007). Monorepo: its subprojects live under
// gateway/; the modulith stays the root project and its build is untouched.
include("gateway:core")
include("gateway:security")
include("gateway:app")
