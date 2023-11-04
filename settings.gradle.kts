rootProject.name = "git-kspr"
include("git-kspr")
include("data-class-fragment")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("${rootProject.projectDir}/libs.versions.toml"))
        }
    }
}
