pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
        // Only the fabric module applies Loom, and only a `-Ploader=fabric` build configures it.
        maven {
            url = uri("https://maven.fabricmc.net/")
            content {
                includeGroupAndSubgroups("net.fabricmc")
                includeGroup("fabric-loom")
            }
        }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sbwnpc"

apply(from = "gradle/sbw-input.settings.gradle.kts")

// Only the selected loader is configured, so one loader's repositories and plugins are never
// needed to build another. See gradle/sbw/ for the profiles and docs/MULTILOADER-DESIGN.md.
val selectedLoader = providers.gradleProperty("loader").orElse("neoforge").get()
include(":common", ":$selectedLoader")
