import org.springframework.boot.gradle.tasks.bundling.BootJar

val jar: Jar by tasks
val bootJar: BootJar by tasks

bootJar.enabled = false
jar.enabled = true

dependencies {
    implementation(project(":domain"))
    implementation("org.springframework:spring-web")

    //Slack API
    implementation("com.slack.api:bolt:${rootProject.extra.get("slackSdkVersion")}")
    //Springboot starter jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    //Local & Test database
    runtimeOnly("com.h2database:h2")
    //MariaDB
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
}

allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.MappedSuperclass")
    annotation("javax.persistence.Embeddable")
}