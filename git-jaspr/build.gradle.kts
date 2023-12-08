@file:Suppress("UnstableApiUsage")

import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.graphql)
    alias(libs.plugins.spotless)
    alias(libs.plugins.graalvm)
    application
}

graalvmNative {
    binaries {
        all {
            resources.autodetect()
        }
        named("main") {
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                    vendor.set(JvmVendorSpec.matching("GraalVM"))
                },
            )
        }
    }
    toolchainDetection.set(true)
}

graphql {
    client {
        sdlEndpoint = "https://docs.github.com/public/schema.docs.graphql"
        queryFileDirectory = "src/graphql"
        packageName = "sims.michael.gitjaspr.generated"
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

    testImplementation(project(":github-dsl-model"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.jgit.junit)
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.jcraft:jsch:0.1.55"))
            .using(module("com.github.mwiede:jsch:0.2.13"))
            .because("See https://github.com/mwiede/jsch#why-should-you-use-this-library")
    }
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.GRAAL_VM)
    }
}

application {
    // Define the main class for the application.
    mainClass.set("sims.michael.gitjaspr.Cli")
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
