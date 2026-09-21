import org.springframework.boot.gradle.tasks.bundling.BootJar

val jarName: String? = findProperty("jarName") as String?
val springBootVersion = rootProject.extra["springBootVersion"] as String
val jacksonVersion = rootProject.extra["jacksonVersion"] as String
val slackSdkVersion = rootProject.extra["slackSdkVersion"] as String
val springAiVersion = rootProject.extra["springAiVersion"] as String

tasks.named<BootJar>("bootJar") {
    if (!jarName.isNullOrBlank()) {
        archiveFileName.set("$jarName.jar")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation(project(":domain"))
    implementation(project(":infrastructure"))
    testFixturesImplementation(project(":infrastructure"))

    api(platform("tools.jackson:jackson-bom:$jacksonVersion"))
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("com.slack.api:slack-api-client:$slackSdkVersion")
    implementation("javax.websocket:javax.websocket-api:1.1")
    runtimeOnly("org.glassfish.tyrus.bundles:tyrus-standalone-client:1.22")

    testImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(testFixtures(project(":domain")))

    testImplementation(testFixtures(project(":infrastructure")))

    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    testFixturesImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    developmentOnly("org.springframework.boot:spring-boot-devtools:$springBootVersion")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
