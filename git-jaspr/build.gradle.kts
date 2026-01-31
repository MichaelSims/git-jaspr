@file:Suppress("UnstableApiUsage")

import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import org.gradle.api.plugins.ApplicationPlugin.APPLICATION_GROUP
import org.gradle.api.plugins.JavaBasePlugin.VERIFICATION_GROUP

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
        all { resources.autodetect() }
        named("main") {
            // The class kotlin.DeprecationLevel ends up getting initialized at build time. During
            // compilation, graalvm presumably attempts to see if a class is annotated with
            // com.oracle.svm.core.hub.Hybrid, which has the side effect of initializing
            // kotlin.DeprecationLevel. This causes the build to fail if we don't explicitly request
            // that it be initialized at build time, which I will do so here.
            //
            // See also:
            // https://github.com/oracle/graal
            //   (source code for com.oracle.svm.hosted.heap.PodFeature and
            //    com.oracle.svm.core.hub.Hybrid)
            // https://www.graalvm.org/21.3/reference-manual/native-image/ClassInitialization/
            // https://www.graalvm.org/21.3/reference-manual/native-image/Options/
            // https://stackoverflow.com/questions/60654455/how-to-fix-try-avoiding-to-initialize-the-class-that-caused-initialization-wit
            // https://stackoverflow.com/questions/78745329/graalvm-native-gradle-plugin-using-nativecompile-fails-using-nativecompile-task
            buildArgs.add("--initialize-at-build-time=kotlin.DeprecationLevel")

            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                    vendor.set(JvmVendorSpec.matching("GraalVM"))
                }
            )
        }
    }
    toolchainDetection.set(true)
}

graphql {
    client {
        sdlEndpoint = "https://docs.github.com/public/fpt/schema.docs.graphql"
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

application {
    // Define the main class for the application.
    mainClass.set("sims.michael.gitjaspr.Cli")
}

val nonDefaultTestTags = mapOf("functional" to "Functional tests")

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            targets {
                all {
                    testTask.configure {
                        useJUnitPlatform { excludeTags(*nonDefaultTestTags.keys.toTypedArray()) }
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
        useJUnitPlatform { includeTags(testTag) }

        testClassesDirs = files(defaultTestSuite.map { it.sources.output.classesDirs })
        classpath = files(defaultTestSuite.map { it.sources.runtimeClasspath })
    }
}

// Allows running sub groups of tests in GitJasprTest.
// The easiest way to run these test groups in IDEA is to go to, f.e., GitJasprDefaultTest and click
// the run button in the gutter. When prompted to choose tasks, choose the `test*` task(s) you want
// to run.
val testGroups = listOf("status", "push", "prBody", "merge", "clean", "dontPush")

for (testTag in testGroups) {
    val taskName = "test" + testTag.replaceFirstChar { char -> char.uppercase() }
    task<Test>(taskName) {
        group = VERIFICATION_GROUP
        useJUnitPlatform { includeTags(testTag) }
    }
}

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

// Create Gradle tasks to run each Jaspr sub-command.
// The most straightforward way to run the main class in IDEA creates a run configuration that uses
// a Gradle task that will be marked up to date unless code changes have been made since the last
// run. This is inconvenient when developing, so the following tasks avoid this by never being up to
// date. To run in IDEA, use CTRL-CTRL to Run Anything, then run (for example):
// `./gradlew :git-jaspr:jaspr-status`
// Configure the various `jasprRun*` properties in `~/.gradle/gradle.properties` to control the
// working directory, log level, etc.
val subCommands = listOf("status", "push", "merge", "auto-merge", "clean", "install-commit-id-hook")

for (subCommand in subCommands) {
    val taskName = "jaspr-$subCommand"
    task<JavaExec>(taskName) {
        group = APPLICATION_GROUP
        mainClass.set("sims.michael.gitjaspr.Cli")
        classpath = sourceSets.main.get().runtimeClasspath
        outputs.upToDateWhen { false }
        args(subCommand, "--log-level=${properties["jasprRunLogLevel"] as? String ?: "INFO"}")
        workingDir =
            file(properties["jasprRunWorkingDir"] as? String ?: project.rootDir.absolutePath)
    }
}
