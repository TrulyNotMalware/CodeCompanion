plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "CodeCompanion"
include("domain")
include("infrastructure")
include("application")
