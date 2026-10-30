pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // Psiphon publishes its Android library as a Maven layout in its own
        // GitHub repository. Scope this nonstandard repository to Psiphon only.
        maven {
            name = "psiphon"
            url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
            content { includeGroup("ca.psiphon") }
        }
    }
}

rootProject.name = "CatClient"
include(":app")
