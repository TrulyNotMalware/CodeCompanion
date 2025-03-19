import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.4.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("java-library")
    id("java-test-fixtures")
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10" apply false
    kotlin("plugin.jpa") version "2.1.10" apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

ext{
    set("kotestVersion", "5.9.0") // https://kotest.io/docs/changelog.html
    set("kotestSpringExtensionVersion", "1.3.0") // https://kotest.io/docs/extensions/spring.html
    set("slackSdkVersion", "1.45.3")
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
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
        //CHECK LATER
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

        //Kotlin logging
        implementation("io.github.oshai:kotlin-logging-jvm:7.0.5")
        testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
        testImplementation("io.kotest:kotest-runner-junit5-jvm:${rootProject.extra.get("kotestVersion")}")
        testImplementation("io.kotest.extensions:kotest-extensions-spring:${rootProject.extra.get("kotestSpringExtensionVersion")}")
        testImplementation("io.kotest:kotest-assertions-core-jvm:${rootProject.extra.get("kotestVersion")}")

    }
}