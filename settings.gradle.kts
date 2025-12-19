plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "git-jaspr"
include("git-jaspr")
include("data-class-fragment")
include("github-dsl-model")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("${rootProject.projectDir}/libs.versions.toml"))
        }
    }
}
