import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    repositories {
        mavenCentral()
        maven {
            name = "jgit-repository"
            url = uri("https://repo.eclipse.org/content/groups/releases/")
        }
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configure<KotlinJvmProjectExtension> {
            jvmToolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            }
        }
    }
}

spotless { kotlinGradle { ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle() } }
