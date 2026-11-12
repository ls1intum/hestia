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

// One command for the tests of all servers: `./gradlew testAll`.
// ExamLense is still a standalone Gradle project with its own wrapper, so it runs through that
// wrapper until it joins this build.
val examlenseServerDir = layout.projectDirectory.dir("apps/examlense/server")

val examlenseTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the ExamLense server tests through its own Gradle wrapper."
    workingDir = examlenseServerDir.asFile
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    commandLine(
        if (windows) listOf("cmd", "/c", "gradlew.bat", "test", "--no-daemon")
        else listOf("./gradlew", "test", "--no-daemon")
    )
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs the tests of all servers and shared libraries."
    dependsOn(
        ":libs:shared-llm:test",
        ":apps:learninggoalhub:server:test",
        ":apps:workshopper:server:test",
        examlenseTest,
    )
}
