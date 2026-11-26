pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
// lets Gradle download the JDK named in gradle/gradle-daemon-jvm.properties when it is not installed
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Takt"
include(":app")
