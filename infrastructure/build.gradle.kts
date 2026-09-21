import org.springframework.boot.gradle.tasks.bundling.BootJar

val jar: Jar by tasks
val bootJar: BootJar by tasks
val springBootVersion: String by rootProject.extra
val jacksonVersion: String by rootProject.extra
val slackSdkVersion: String by rootProject.extra

bootJar.enabled = false
jar.enabled = true

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
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
