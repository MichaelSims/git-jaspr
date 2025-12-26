plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.ksp)
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
    implementation(project(":data-class-fragment"))
    ksp(project(":data-class-fragment"))

    annotationProcessor(libs.auto.service)
    implementation(libs.auto.service)
    kapt(libs.auto.service)
}

tasks.test { useJUnitPlatform() }

spotless {
    kotlin {
        toggleOffOn()
        targetExclude("build/**/*")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        toggleOffOn()
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
