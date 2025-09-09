import org.springframework.boot.gradle.tasks.bundling.BootJar

val jarName: String? = findProperty("jarName") as String?

tasks.named<BootJar>("bootJar") {
    if (!jarName.isNullOrBlank()) {
        archiveFileName.set("$jarName.jar")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    //Undertow
    implementation("org.springframework.boot:spring-boot-starter-undertow")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation ("org.springframework.boot:spring-boot-starter-test")

    //AOP
    implementation("org.springframework.boot:spring-boot-starter-aop")

    //rest docs
    testFixturesImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}