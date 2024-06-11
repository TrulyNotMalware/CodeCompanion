plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
rootProject.name = "CodeCompanion"
include("domain")
include("infrastructure")
include("application")
