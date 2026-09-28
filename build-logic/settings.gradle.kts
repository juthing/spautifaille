dependencyResolutionManagement {
    repositories {
        google()
        // Miroir Google de Maven Central (évite les 429 de repo.maven.apache.org)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "build-logic"
include(":convention")
