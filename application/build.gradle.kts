import org.springframework.boot.gradle.tasks.bundling.BootJar

val jarName: String? = findProperty("jarName") as String?

tasks.named<BootJar>("bootJar") {
    if (!jarName.isNullOrBlank()) {
        archiveFileName.set("$jarName.jar")
    }
}

dependencies {
    // Spring-boot bom
    implementation(
        platform("org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}"),
    )
    testFixturesImplementation(
        platform("org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}"),
    )

    implementation(project(":domain"))
    implementation(project(":infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    // Springboot 4 does not support undertow.
    implementation("org.springframework.boot:spring-boot-starter-jetty")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Domain test fixtures
    testImplementation(testFixtures(project(":domain")))
    testFixturesImplementation(testFixtures(project(":domain")))

    // AOP
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // rest docs
    testFixturesImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    developmentOnly("org.springframework.boot:spring-boot-devtools:${rootProject.extra.get("springBootVersion")}")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
