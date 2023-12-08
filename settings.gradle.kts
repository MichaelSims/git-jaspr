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
