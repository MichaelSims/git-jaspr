@file:Suppress("UnstableApiUsage")

import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.graphql)
    alias(libs.plugins.spotless)
    application
}

graphql {
    client {
        sdlEndpoint = "https://docs.github.com/public/schema.docs.graphql"
        queryFileDirectory = "src/graphql"
        packageName = "sims.michael.gitkspr.generated"
        serializer = GraphQLSerializer.KOTLINX
    }
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.graphql.kotlin.ktor.client)
    implementation(libs.ktor.client.auth)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.jgit)
    implementation(libs.jgit.ssh)
    implementation(libs.zt.exec)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":data-class-fragment"))

    annotationProcessor(libs.auto.service)
    implementation(libs.auto.service)
    kapt(libs.auto.service)

    kaptTest(project(":data-class-fragment"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.jgit.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.inline)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

application {
    // Define the main class for the application.
    mainClass.set("sims.michael.gitkspr.Cli")
}

val nonDefaultTestTags = mapOf(
    "functional" to "Functional tests",
)

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            targets {
                all {
                    testTask.configure {
                        useJUnitPlatform {
                            excludeTags(*nonDefaultTestTags.keys.toTypedArray())
                        }
                    }
                }
            }
        }
    }
}

val defaultTestSuite = testing.suites.named<JvmTestSuite>("test")

nonDefaultTestTags.forEach { (testTag, testDescription) ->
    task<Test>(testTag) {
        description = testDescription
        useJUnitPlatform {
            includeTags(testTag)
        }

        testClassesDirs = files(defaultTestSuite.map { it.sources.output.classesDirs })
        classpath = files(defaultTestSuite.map { it.sources.runtimeClasspath })
    }
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
