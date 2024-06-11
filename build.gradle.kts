import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.2.6" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
    id("java-library")
    id("java-test-fixtures")
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

ext{
    set("kotestVersion", "5.9.0") // https://kotest.io/docs/changelog.html
    set("kotestSpringExtensionVersion", "1.3.0") // https://kotest.io/docs/extensions/spring.html
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

    tasks.withType<JavaCompile>{
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-reflect")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
        testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
        testImplementation("io.kotest:kotest-runner-junit5-jvm:${rootProject.extra.get("kotestVersion")}")
        testImplementation("io.kotest.extensions:kotest-extensions-spring:${rootProject.extra.get("kotestSpringExtensionVersion")}")
        testImplementation("io.kotest:kotest-assertions-core-jvm:${rootProject.extra.get("kotestVersion")}")

    }
}