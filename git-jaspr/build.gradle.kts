@file:Suppress("UnstableApiUsage")

import org.gradle.api.plugins.ApplicationPlugin.APPLICATION_GROUP
import org.gradle.api.plugins.JavaBasePlugin.VERIFICATION_GROUP

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.apollo)
    alias(libs.plugins.spotless)
    alias(libs.plugins.graalvm)
    alias(libs.plugins.asciidoctor)
    application
}

val generateVersionFile by
    tasks.registering {
        val outputDir = layout.buildDirectory.dir("generated/source/version")
        outputs.dir(outputDir)
        // Always execute but only write when content changes to preserve compileKotlin caching.
        outputs.upToDateWhen { false }
        doLast {
            val version =
                try {
                    providers
                        .exec { commandLine("git", "describe", "--tags") }
                        .standardOutput
                        .asText
                        .get()
                        .trim()
                } catch (_: Exception) {
                    "undefined"
                }
            val file = outputDir.get().asFile.resolve("sims/michael/gitjaspr/Version.kt")
            val content =
                """
                |package sims.michael.gitjaspr
                |
                |const val VERSION = "$version"
                |
                |"""
                    .trimMargin()
            if (!file.exists() || file.readText() != content) {
                file.parentFile.mkdirs()
                file.writeText(content)
            }
        }
    }

sourceSets.main { kotlin.srcDir(generateVersionFile) }

graalvmNative {
    binaries {
        all { resources.autodetect() }
        named("main") {
            imageName.set("jaspr")
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

val gitHubSchemaUrl = "https://docs.github.com/public/fpt/schema.docs.graphql"
val gitHubSchemaFile = file("src/graphql/schema.graphqls")

task("downloadGitHubSchema") {
    group = "apollo"
    description = "Downloads the GitHub GraphQL schema"
    doLast { gitHubSchemaFile.writeText(uri(gitHubSchemaUrl).toURL().readText()) }
}

apollo {
    service("github") {
        srcDir("src/graphql")
        packageName.set("sims.michael.gitjaspr.generated")
        schemaFiles.from(gitHubSchemaFile)
        codegenModels.set("responseBased")
        introspection {
            endpointUrl.set("https://docs.github.com/public/fpt/schema.docs.graphql")
            schemaFile.set(file("src/graphql/schema.graphqls"))
        }
        // Map GitHub's custom scalars to String
        mapScalar("URI", "kotlin.String")
        mapScalar("DateTime", "kotlin.String")
        mapScalar("GitObjectID", "kotlin.String")
        mapScalar("HTML", "kotlin.String")
        mapScalar("GitSSHRemote", "kotlin.String")
        mapScalar("GitTimestamp", "kotlin.String")
        mapScalar("Base64String", "kotlin.String")
        mapScalar("PreciseDateTime", "kotlin.String")
        mapScalar("X509Certificate", "kotlin.String")
        mapScalar("BigInt", "kotlin.String")
    }
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.clikt.markdown)
    implementation(libs.apollo.runtime)
    implementation(libs.apollo.engine.ktor)
    implementation(libs.ktor.client.cio)
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
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
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
    applicationName = "jaspr"
}

val generateCompletions by
    tasks.registering(JavaExec::class) {
        description = "Generate shell completion scripts for bash, zsh, and fish"
        mainClass.set("sims.michael.gitjaspr.GenerateCompletions")
        classpath = sourceSets.main.get().runtimeClasspath
        val outputDir = layout.buildDirectory.dir("completions")
        outputs.dir(outputDir)
        args(outputDir.get().asFile.absolutePath)
    }

tasks.named<org.asciidoctor.gradle.jvm.AsciidoctorTask>("asciidoctor") {
    sourceDir(file("src/docs/asciidoc"))
    setOutputDir(layout.buildDirectory.dir("manpage"))
    outputOptions { backends("manpage") }
}

val nonDefaultTestTags =
    mapOf("functional" to "Functional tests", "nativeImageMetadata" to "Native image metadata")

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

tasks.named<Test>("nativeImageMetadata") {
    group = VERIFICATION_GROUP
    val metadataDir = "src/main/resources/META-INF/native-image"
    // The native-image-agent is only available on GraalVM JDKs. When running on a standard JDK,
    // the tests still run (useful for verification) but don't collect metadata.
    val javaHome =
        javaLauncher.map { it.metadata.installationPath.asFile }.orNull
            ?: File(System.getProperty("java.home"))
    val agentLib =
        javaHome.resolve("lib").listFiles().orEmpty().any { file ->
            file.name.contains("native-image-agent")
        }
    if (agentLib) {
        val filterFile = file("src/test/resources/native-image-agent-access-filter.json")
        jvmArgs(
            "-agentlib:native-image-agent=access-filter-file=$filterFile,config-merge-dir=$metadataDir"
        )
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
val subCommands =
    listOf("status", "push", "merge", "auto-merge", "clean", "init", "install-commit-id-hook")

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
