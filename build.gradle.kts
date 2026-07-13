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
