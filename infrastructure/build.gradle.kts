import org.springframework.boot.gradle.tasks.bundling.BootJar

val springBootVersion = rootProject.extra["springBootVersion"] as String
val jacksonVersion = rootProject.extra["jacksonVersion"] as String
val slackSdkVersion = rootProject.extra["slackSdkVersion"] as String

tasks.named<BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation(project(":domain"))
    implementation("org.springframework:spring-web")

    api(platform("tools.jackson:jackson-bom:$jacksonVersion"))
    implementation("tools.jackson.module:jackson-module-kotlin")

    api("org.springframework.boot:spring-boot-starter-kafka")
    implementation("com.slack.api:slack-api-model:$slackSdkVersion")
    implementation("com.slack.api:slack-api-client:$slackSdkVersion")
    implementation("com.slack.api:slack-app-backend:$slackSdkVersion")
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")

    testImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-extensions-spring")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
