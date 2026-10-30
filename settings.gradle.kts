pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "illumera"
include(":app")
include(":playbackcore")
include(":assrender")
// The module is now located directly in the assrender/ folder
project(":assrender").projectDir = file("assrender")
