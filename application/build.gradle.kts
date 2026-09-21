import org.springframework.boot.gradle.tasks.bundling.BootJar

val jarName: String? = findProperty("jarName") as String?
val springBootVersion: String by rootProject.extra
val jacksonVersion: String by rootProject.extra
val slackSdkVersion: String by rootProject.extra
val springAiVersion: String by rootProject.extra

tasks.named<BootJar>("bootJar") {
    if (!jarName.isNullOrBlank()) {
        archiveFileName.set("$jarName.jar")
    }
}

dependencies {
    // Spring-boot bom
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation(project(":domain"))
    implementation(project(":infrastructure"))
    testFixturesImplementation(project(":infrastructure"))

    // Jackson — declared per-module so :domain's classpath stays Jackson-free
    api(platform("tools.jackson:jackson-bom:$jacksonVersion"))
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    // Springboot 4 does not support undertow.
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Slack Socket Mode — local-only inbound transport (gated to the `local` Spring profile).
    // slack-api-client provides SocketModeClient; tyrus is its default WebSocket backend.
    implementation("com.slack.api:slack-api-client:$slackSdkVersion")
    implementation("javax.websocket:javax.websocket-api:1.1")
    runtimeOnly("org.glassfish.tyrus.bundles:tyrus-standalone-client:1.20")

    // Domain test fixtures
    testImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(testFixtures(project(":domain")))

    // Infrastructure test fixtures (Slack event payload/event builders)
    testImplementation(testFixtures(project(":infrastructure")))

    // AOP
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // MCP server — domain tools for the AI agent lane (streamable HTTP on /mcp)
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // rest docs
    testFixturesImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    developmentOnly("org.springframework.boot:spring-boot-devtools:$springBootVersion")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
