import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.kapt)
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

    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.kotlinpoet.metadata)
    implementation(libs.ksp.api)
    implementation(libs.arrowkt.core)
    kapt(libs.auto.service)
    annotationProcessor(libs.auto.service)
    compileOnly(libs.auto.service)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
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

tasks.withType<KotlinCompile> {
    compilerOptions { freeCompilerArgs.addAll(listOf("-Xcontext-parameters")) }
}
