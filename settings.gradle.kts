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

// Two modules, split by what a JVM can run.
//
// :protocol is every byte of the five wire formats and has NO Android dependency,
// so all of it is testable with `./gradlew :protocol:test` — no phone, no adb, no
// pairing. :app is the parts that genuinely need a device: RFCOMM sockets, GATT,
// LE scanning, permissions, UI.
//
// The line is drawn at I/O, not at "app vs library": the transport INTERFACE lives
// in :protocol and only its implementations are in :app, which is what lets a
// recorded session be replayed against the real driver code off-device.
include(":protocol")
include(":app")
