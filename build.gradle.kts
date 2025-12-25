import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    id("org.springframework.boot") version "4.0.1" apply false
    id("java-library")
    id("java-test-fixtures")
    id("org.jlleitschuh.gradle.ktlint").version("14.0.1")
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0" apply false
    kotlin("plugin.jpa") version "2.3.0" apply false
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
    set("kotestVersion", "6.0.3") // https://kotest.io/docs/changelog.html
    set("slackSdkVersion", "1.45.4")
    set("mockkVersion", "1.14.6")
    set("springBootVersion", "4.0.0")
    set("jacksonVersion", "3.0.2")
    set("kotlinLoggingVersion", "7.0.13")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_25
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

    tasks.withType<KotlinJvmCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-jvm-default=enable",
                "-java-parameters",
            )
        }
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
* BOM version for kotlinx-coroutines. When that plugin is applied, Spring’s dependency
* management can pin or supersede BOM coordinates, which makes it hard to import and
* control the desired kotlinx-coroutines BOM via Gradle platforms.
* */
subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

    dependencies {
        // Kotest-bom
        implementation(platform("io.kotest:kotest-bom:${rootProject.extra.get("kotestVersion")}"))
        // Jackson-bom
        implementation(platform("tools.jackson:jackson-bom:${rootProject.extra.get("jacksonVersion")}"))

        implementation(kotlin("reflect"))
        implementation("tools.jackson.module:jackson-module-kotlin")

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
