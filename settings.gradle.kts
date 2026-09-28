pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        // Miroir Google de Maven Central (évite les 429 de repo.maven.apache.org)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Miroir Google de Maven Central (évite les 429 de repo.maven.apache.org)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io") {
            // JitPack uniquement pour NewPipeExtractor et ses dépendances (nanojson).
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "spautifaille"
include(":app", ":domain", ":data", ":player", ":ui")
