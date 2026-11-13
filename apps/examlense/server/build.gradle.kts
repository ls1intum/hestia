plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

tasks.bootJar {
    archiveBaseName.set("examlense-server")
}

tasks.jar {
    archiveBaseName.set("examlense-server")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-saml2-service-provider")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // OpenAPI spec + Swagger UI.
    implementation(libs.springdoc.webmvc.ui)

    // Persistence: JPA + Postgres + Flyway (migration off Supabase PostgREST → local Postgres)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // PDFBox: page rasterization (vision strategies) + per-page text stripping (TEXT_ONLY strategy)
    implementation(libs.pdfbox)

    // docx4j + Apache FOP: convert uploaded Word .docx to PDF at upload time so the
    // rest of the (PDF-only) parse pipeline is unchanged. Pure-Java, no system package.
    // docx4j-JAXB-ReferenceImpl bundles a JAXB impl (not in the JDK on 21).
    implementation(libs.docx4j.jaxb.referenceimpl)
    implementation(libs.docx4j.export.fo)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    // Testcontainers: a real Postgres for the boot/migration/DB-integrity/isolation
    // tests. The pure unit tests don't touch it. Requires a Docker daemon locally
    // and in CI (GitHub-hosted runners provide one). Version managed by Spring Boot.
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
