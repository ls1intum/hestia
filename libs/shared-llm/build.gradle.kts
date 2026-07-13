plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.14")
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.6")
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
