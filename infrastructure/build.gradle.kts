import org.springframework.boot.gradle.tasks.bundling.BootJar

val jar: Jar by tasks
val bootJar: BootJar by tasks

bootJar.enabled = false
jar.enabled = true

dependencies {
    // Spring-boot bom
    implementation(
        platform(
            "org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}",
        ),
    )
    testImplementation(
        platform(
            "org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}",
        ),
    )
    testFixturesImplementation(
        platform(
            "org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}",
        ),
    )

    implementation(project(":domain"))
    implementation("org.springframework:spring-web")
//    api("org.springframework.retry:spring-retry") now spring core

    // CDC
    api("org.springframework.boot:spring-boot-starter-kafka")
    // Slack API
    implementation("com.slack.api:slack-api-model:${rootProject.extra.get("slackSdkVersion")}")
    implementation("com.slack.api:slack-api-client:${rootProject.extra.get("slackSdkVersion")}")
    implementation("com.slack.api:slack-app-backend:${rootProject.extra.get("slackSdkVersion")}")
    // Springboot starter jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // Local & Test database
    runtimeOnly("com.h2database:h2")
    // MariaDB
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    // MCP Server
    api("org.springframework.ai:spring-ai-starter-mcp-server-webmvc:1.0.1")

    // Test code
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")

    // Domain test fixtures
    testImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
