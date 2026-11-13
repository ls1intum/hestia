plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-saml2-service-provider")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.flywaydb:flyway-core")
    implementation(libs.tika.core)
    implementation(libs.tika.langdetect.optimaize)
    implementation(libs.tika.parsers.standard)
    // Used directly for PDF bookmark/outline extraction (session boundaries); also a transitive Tika dep.
    implementation(libs.pdfbox)
    implementation(libs.opencsv)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
