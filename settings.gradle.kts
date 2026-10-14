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

rootProject.name = "ZapretKVN"
include(":app")
include(":app-updater")
include(":network-bootstrap")
include(":wireguard-import")
include(":core:ui-model")
include(":core:designsystem")
