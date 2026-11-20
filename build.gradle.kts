plugins {
    java
}

allprojects {
    group = "de.tum.cit.hestia"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven { url = uri("https://build.shibboleth.net/nexus/content/repositories/releases/") }
    }
}

// One command for all server tests: `./gradlew testAll`.
// `./gradlew build` runs the same tests and also assembles the server.
tasks.register("testAll") {
    group = "verification"
    description = "Runs the tests of the server."
    dependsOn(
        ":server:test",
    )
}
