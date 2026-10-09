import java.net.URI

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
        maven(url = "https://nexus-external.vkteam.ru/repository/vkid-sdk-android/")
        maven(url = "https://nexus-external.vkteam.ru/repository/maven/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = URI("https://nexus-external.vkteam.ru/repository/vkid-sdk-android/")
        }
        maven {
            url = URI("https://nexus-external.vkteam.ru/repository/maven/")
        }
    }
}

rootProject.name = "VKMusic"
include(":app")
