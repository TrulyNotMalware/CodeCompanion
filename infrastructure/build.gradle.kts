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
}
