plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.spotless)
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
    maven {
        name = "jgit-repository"
        url = uri("https://repo.eclipse.org/content/groups/releases/")
    }
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        toggleOffOn()
        ktlint().setEditorConfigPath("$rootDir/.editorconfig")
        targetExclude("build/generated/")
    }
    kotlinGradle {
        toggleOffOn()
        ktlint()
    }
}
