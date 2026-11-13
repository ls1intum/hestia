plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
        mavenBom(libs.spring.ai.bom.get().toString())
    }
}

dependencies {
    api("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.boot:spring-boot")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
