import java.util.Properties

plugins {
    id("fabric-loom") version "1.17.20"
    id("sbwnpc.jvm-conventions")
}

version = rootProject.version
group = "com.sbwnpc.squad"

val fabricVersions = Properties().apply { rootProject.file("gradle/fabric.properties").inputStream().use { load(it) } }

@Suppress("UNCHECKED_CAST")
val sbwInput = rootProject.extensions.extraProperties["sbwInput"] as Map<String, String>
val sbwCoordinate = sbwInput.getValue("profile.coordinate")

repositories {
    mavenCentral()
    maven {
        name = "Pinned SBW (ReFabricated)"
        url = uri("https://api.modrinth.com/maven")
        content { includeModule("maven.modrinth", "XU8zWdjt") }
    }
    maven {
        name = "SmartBrainLib (Cloudsmith)"
        url = uri("https://dl.cloudsmith.io/public/tslat/sbl/maven/")
        content { includeGroup("net.tslat.smartbrainlib") }
    }
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/")
    }
    maven {
        name = "Ladysnake"
        url = uri("https://maven.ladysnake.org/releases")
    }
}

base {
    archivesName.set(project.property("mod_id") as String)
}

// The shared sources are recompiled here with Loom, on Mojang names, as the
// NeoForge module does with ModDevGradle.
val commonKotlin by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val commonResources by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val commonGeneratedKotlin by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val commonJava by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
sourceSets.main.get().java.srcDir(commonJava)
sourceSets.main.get().resources.srcDir(commonResources)
kotlin {
    sourceSets.main {
        kotlin.srcDir(commonKotlin)
        kotlin.srcDir(commonGeneratedKotlin)
    }
}

dependencies {
    add(commonKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonKotlin")))
    add(commonResources.name, project(mapOf("path" to ":common", "configuration" to "commonResources")))
    add(commonGeneratedKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonGeneratedKotlin")))
    add(commonJava.name, project(mapOf("path" to ":common", "configuration" to "commonJava")))

    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // Mojang names without Parchment: it adds parameter names only, and its host redirects to
    // ldtteam.jfrog.io, which the cloud network policy does not allow.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${fabricVersions.getProperty("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${fabricVersions.getProperty("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${fabricVersions.getProperty("fabric_kotlin_version")}")
    modImplementation("net.tslat.smartbrainlib:SmartBrainLib-fabric-1.21.1:${project.property("smartbrainlib_version")}")

    // Immutable release input; the adapter in this module compiles against it.
    modCompileOnly(sbwCoordinate)
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

val modJar by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
// Named by loader like the NeoForge jar (sbwnpc-<version>-mc<mc>-fabric.jar), so jars of different loaders never share a name.
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") { archiveClassifier.set("fabric") }
artifacts.add(modJar.name, tasks.named("remapJar"))
