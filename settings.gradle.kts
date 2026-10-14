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
include(":core:model")
include(":core:designsystem")
include(":feature:home")
include(":feature:servers")
include(":feature:routing")
include(":feature:settings")
