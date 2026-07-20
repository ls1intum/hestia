plugins {
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.6")
    }
}

tasks.bootJar {
    archiveBaseName.set("learninggoalhub-server")
}

tasks.jar {
    archiveBaseName.set("learninggoalhub-server")
}

dependencies {
    implementation(project(":libs:shared-llm"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.hibernate.orm:hibernate-vector:6.6.49.Final")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.flywaydb:flyway-core")
    implementation("org.apache.tika:tika-core:3.0.0")
    implementation("org.apache.tika:tika-langdetect-optimaize:3.0.0")
    implementation("org.apache.tika:tika-parsers-standard-package:3.0.0")
    // Used directly for PDF bookmark/outline extraction (session boundaries); also a transitive Tika dep.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("com.opencsv:opencsv:5.9")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
