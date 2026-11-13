// The one Hestia server: the modules materials (from LearningGoalHub), teaching (from
// Workshopper) and assessment (from ExamLense) plus core, in one Spring Boot application.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

tasks.bootJar {
    archiveBaseName.set("hestia-server")
}

tasks.jar {
    archiveBaseName.set("hestia-server")
}

dependencies {
    implementation(project(":libs:shared-llm"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-saml2-service-provider")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation(libs.springdoc.webmvc.ui)

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Documents: parsing (materials), PDF rendering (all), Word and PowerPoint (assessment, teaching)
    implementation(libs.tika.core)
    implementation(libs.tika.langdetect.optimaize)
    implementation(libs.tika.parsers.standard)
    implementation(libs.pdfbox)
    implementation(libs.opencsv)
    implementation(libs.poi.ooxml)
    // docx4j + Apache FOP convert uploaded Word .docx to PDF (assessment). docx4j-JAXB-ReferenceImpl
    // bundles a JAXB implementation, which the JDK no longer ships.
    implementation(libs.docx4j.jaxb.referenceimpl)
    implementation(libs.docx4j.export.fo)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The three modules' tests share one JVM, and Spring keeps every distinct test context cached;
    // Gradle's default of 512 MB runs out. A smaller cache also closes idle contexts' DB pools.
    maxHeapSize = "2g"
    systemProperty("spring.test.context.cache.maxSize", "16")
}
