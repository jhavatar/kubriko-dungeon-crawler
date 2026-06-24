pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kubriko-dungeon-crawler"

include(":plugin-tilemap")
include(":plugin-dungeon-crawler")
include(":app")
