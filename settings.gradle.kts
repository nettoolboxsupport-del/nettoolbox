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
        // usb-serial-for-android is published only on JitPack. Restricted to
        // its one group: JitPack builds whatever a GitHub repository contains,
        // and an unfiltered entry here would let it answer for any dependency
        // Maven Central does not have.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "NetToolbox"

include(":app")
include(":baselineprofile")

include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:permissions")
include(":core:terminal")
include(":core:ui")

include(":feature:cellular")
include(":feature:fileserver")
include(":feature:iperf")
include(":feature:map")
include(":feature:serial")
include(":feature:ssh")
include(":feature:tools")
include(":feature:wifi")

include(":native:icmp")
include(":native:iperf3")
include(":native:vterm")
