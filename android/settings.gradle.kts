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

rootProject.name = "pvp-search-app"
include(":engine")
// `-PengineOnly=true` skips the Android module, for machines without the Android SDK
// (e.g. running the engine tests on a server).
if (providers.gradleProperty("engineOnly").orNull != "true") {
    include(":app")
}
