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

// One command for the tests of all servers and shared libraries: `./gradlew testAll`.
// `./gradlew build` runs the same tests and also assembles every server.
tasks.register("testAll") {
    group = "verification"
    description = "Runs the tests of all servers and shared libraries."
    dependsOn(
        ":libs:shared-llm:test",
        ":apps:learninggoalhub:server:test",
        ":apps:workshopper:server:test",
        ":apps:examlense:server:test",
    )
}
