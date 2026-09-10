pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sbwnpc"

// Разрабатываем поверх ЛОКАЛЬНОГО снапшота SuperbWarfare (папка пуллится/обновляется отдельно,
// см. .gitignore — она не часть этого репозитория), а не поверх опубликованного релиза,
// потому что используем внутренние API мода (GunShootGoal, AutoAimableEntity/TowerAI, ArtilleryEntity и т.д.),
// которые могут ещё не быть в последнем релизе на Modrinth/CurseForge.
//
// Gradle будет собирать SuperbWarfare как отдельный composite build при первой сборке —
// это тяжелее и медленнее обычной maven-зависимости, но гарантирует совпадение API с тем,
// что реально лежит в ./SuperbWarfare на момент сборки.
includeBuild("SuperbWarfare") {
    dependencySubstitution {
        substitute(module("com.atsuishio.superbwarfare:superbwarfare"))
            .using(project(":"))
    }
}
