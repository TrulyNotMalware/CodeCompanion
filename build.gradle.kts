import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("java-library")
    id("java-test-fixtures")
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0" apply false
    kotlin("plugin.jpa") version "2.4.0" apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

ext {
    set("kotestVersion", "6.2.0") // https://kotest.io/docs/changelog.html
    set("slackSdkVersion", "1.49.0")
    set("mockkVersion", "1.14.11")
    set("springBootVersion", "4.1.0")
    set("jacksonVersion", "3.2.0")
    set("kotlinLoggingVersion", "8.0.4")
}

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

/*
 * Removed the `io.spring.dependency-management` plugin to explicitly override the
 * BOM version for kotlinx-coroutines. When that plugin is applied, Spring's dependency
 * management can pin or supersede BOM coordinates, which makes it hard to import and
 * control the desired kotlinx-coroutines BOM via Gradle platforms.
 */
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

    dependencies {
        // BOM platforms — use api so they propagate to testFixtures and other configurations.
        // Jackson is intentionally NOT injected here: domain must stay Jackson-free, so the
        // modules that actually serialize (application/infrastructure) declare it themselves.
        api(platform("io.kotest:kotest-bom:${rootProject.extra.get("kotestVersion")}"))

        implementation(kotlin("reflect"))

        // Kotlin logging
        implementation("io.github.oshai:kotlin-logging-jvm:${rootProject.extra.get("kotlinLoggingVersion")}")
        testFixturesImplementation(kotlin("reflect"))

        testImplementation("io.mockk:mockk:${rootProject.extra.get("mockkVersion")}")
        testFixturesImplementation("io.mockk:mockk:${rootProject.extra.get("mockkVersion")}")
        testImplementation("io.kotest:kotest-runner-junit5")
        testImplementation("io.kotest:kotest-extensions-spring")
        testImplementation("io.kotest:kotest-assertions-core")
    }
}
