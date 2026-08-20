pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "NetToolbox"

include(":app")
include(":baselineprofile")

include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:permissions")
include(":core:ui")

include(":feature:cellular")
include(":feature:iperf")
include(":feature:map")
include(":feature:ssh")
include(":feature:tools")
include(":feature:wifi")

include(":native:icmp")
include(":native:iperf3")
include(":native:vterm")
