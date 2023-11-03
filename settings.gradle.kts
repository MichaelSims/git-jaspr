rootProject.name = "git-kspr"
include("git-kspr")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("${rootProject.projectDir}/libs.versions.toml"))
        }
    }
}