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
    testFixturesImplementation(
        platform(
            "org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}",
        ),
    )

    implementation(project(":domain"))
    implementation("org.springframework:spring-web")
    api("org.springframework.retry:spring-retry")
    // CDC
    api("org.springframework.kafka:spring-kafka")
    // Slack API
    implementation(
        "com.slack.api:bolt:${rootProject.extra.get(
            "slackSdkVersion",
        )}",
    )
    // Springboot starter jpa
    api(
        "org.springframework.boot:spring-boot-starter-data-jpa",
    )

    // https://mvnrepository.com/artifact/io.kubernetes/client-java
    implementation("io.kubernetes:client-java:22.0.0")

    // Local & Test database
    runtimeOnly("com.h2database:h2")
    // MariaDB
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    // MCP Server
    api(
        "org.springframework.ai:spring-ai-starter-mcp-server-webmvc:1.0.1",
    )
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
