import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    id("org.springframework.boot") version "3.5.5" apply false
    id("java-library")
    id("java-test-fixtures")
    id("org.jlleitschuh.gradle.ktlint").version("13.1.0")
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.spring") version "2.2.10" apply false
    kotlin("plugin.jpa") version "2.2.10" apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

ext {
    set("kotestVersion", "6.0.3") // https://kotest.io/docs/changelog.html
    set("slackSdkVersion", "1.45.3")
    set("mockkVersion", "1.14.5")
    set("springBootVersion", "3.5.5")
    set("jacksonVersion", "2.19.2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
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

    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
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
        implementation(
            platform(
                "io.kotest:kotest-bom:${rootProject.extra.get(
                    "kotestVersion",
                )}",
            ),
        )
        // Jackson-bom
        implementation(
            platform(
                "com.fasterxml.jackson:jackson-bom:${rootProject.extra.get(
                    "jacksonVersion",
                )}",
            ),
        )

        implementation(kotlin("reflect"))
        implementation(
            "com.fasterxml.jackson.module:jackson-module-kotlin",
        )
        implementation(
            "com.fasterxml.jackson.datatype:jackson-datatype-jdk8",
        )
        implementation(
            "com.fasterxml.jackson.datatype:jackson-datatype-jsr310",
        )

        // Kotlin logging
        implementation(
            "io.github.oshai:kotlin-logging-jvm:7.0.13",
        )
        testFixturesImplementation(kotlin("reflect"))

        testImplementation(
            "io.mockk:mockk:${rootProject.extra.get(
                "mockkVersion",
            )}",
        )
        testImplementation("io.kotest:kotest-runner-junit5")
        testImplementation(
            "io.kotest:kotest-extensions-spring",
        )
        testImplementation(
            "io.kotest:kotest-assertions-core",
        )
    }
}
