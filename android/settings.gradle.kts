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

rootProject.name = "ZeroConvert-IME"
// One application module. `app/` holds the shared sources and assets that
// `:rebuilt` compiles; it is not a Gradle module of its own, so the version,
// SDK levels and native build cannot drift between two hosts.
include(":rebuilt")
