plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.kapt) apply false
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
