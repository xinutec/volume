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

rootProject.name = "volume"

// Task #783: an RFCOMM probe, not the app. It exists to answer whether a
// headphone's proprietary control channel is speakable at all, and to hand #785
// the raw bytes it is built from. Deliberately one module and no UI framework.
include(":app")
