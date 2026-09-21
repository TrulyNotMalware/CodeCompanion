import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("java-library")
    id("java-test-fixtures")
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.10" apply false
    kotlin("plugin.jpa") version "2.4.10" apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

// Dependabot's Gradle parser only reads `extra["x"] = "…"` / `extra.set` declarations, not an `ext {}` block.
extra["kotestVersion"] = "6.2.5"
extra["slackSdkVersion"] = "1.51.0"
extra["mockkVersion"] = "1.14.11"
extra["springBootVersion"] = "4.1.1"
extra["jacksonVersion"] = "3.2.2"
extra["kotlinLoggingVersion"] = "8.0.4"
extra["springAiVersion"] = "2.0.1"

val kotestVersion = extra["kotestVersion"] as String
val mockkVersion = extra["mockkVersion"] as String
val kotlinLoggingVersion = extra["kotlinLoggingVersion"] as String

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-java-parameters",
            "-Xjvm-default=all",
        )
    }
}

allprojects {
    group = "dev.notypie"
    version = "alpha"

    repositories {
        mavenCentral()
    }

    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    ktlint {
        reporters {
            reporter(
                org.jlleitschuh.gradle.ktlint.reporter.ReporterType.JSON,
            )
        }
    }

    tasks.withType<GenerateReportsTask> {
        reportsOutputDirectory.set(
            rootProject.layout.buildDirectory.dir(
                "reports/ktlint/${project.name}",
            ),
        )
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs(
            "-Xmx4g",
            "-Dfile.encoding=UTF-8",
            "-XX:+EnableDynamicAgentLoading",
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util=ALL-UNNAMED",
        )
    }
}

// io.spring.dependency-management is intentionally not applied — it can override the kotlinx-coroutines BOM version.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

    dependencies {
        api(platform("io.kotest:kotest-bom:$kotestVersion"))

        implementation(kotlin("reflect"))

        implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
        testFixturesImplementation(kotlin("reflect"))

        testImplementation("io.mockk:mockk:$mockkVersion")
        testFixturesImplementation("io.mockk:mockk:$mockkVersion")
        testImplementation("io.kotest:kotest-runner-junit5")
        testImplementation("io.kotest:kotest-assertions-core")
    }
}
