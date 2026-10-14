pluginManagement {
    repositories {
        // maven.google.com is the canonical host for AGP and AndroidX.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-checker"
include(":core", ":app")
