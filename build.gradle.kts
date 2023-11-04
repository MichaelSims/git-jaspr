plugins {
    alias(libs.plugins.kotlin)
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
