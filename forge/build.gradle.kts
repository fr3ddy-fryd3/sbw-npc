import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.neoforged.moddev.legacyforge")
    id("sbwnpc.jvm-conventions")
}

// Named for the Minecraft it runs on (1.20.1), not the NeoForge line: sbwnpc-<version>-mc1.20.1-forge.jar.
group = "com.sbwnpc.squad"

val forgeVersions = Properties().apply { rootProject.file("gradle/forge.properties").inputStream().use { load(it) } }
version = "${project.property("mod_version")}-mc${forgeVersions.getProperty("forge_minecraft_version")}"

@Suppress("UNCHECKED_CAST")
val sbwInput = rootProject.extensions.extraProperties["sbwInput"] as Map<String, String>
val sbwCoordinate = sbwInput.getValue("profile.coordinate")

repositories {
    mavenCentral()
    maven {
        name = "Pinned SBW (Forge 1.20.1)"
        url = uri("https://api.modrinth.com/maven")
        content { includeModule("maven.modrinth", "Cd3DYqzn") }
    }
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }
    maven {
        name = "SmartBrainLib (Cloudsmith)"
        url = uri("https://dl.cloudsmith.io/public/tslat/sbl/maven/")
        content { includeGroup("net.tslat.smartbrainlib") }
    }
}

base {
    archivesName.set(project.property("mod_id") as String)
}

// Forge 1.20.1 runs on Java 17: the output targets it, whatever JDK compiles it.
kotlin.compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xjdk-release=17")
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

legacyForge {
    version = forgeVersions.getProperty("forge_version")
    // Mojang names for the shared sources, as on the other loaders; reobfuscated on packaging.
    mods {
        create(project.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

// The shared sources are recompiled here, as the other loader modules do. They do not compile
// against 1.20.1 yet: see docs/LOADER-PORTS.md for the 441 errors this module starts from.
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

    implementation("thedarkcolour:kotlinforforge:${forgeVersions.getProperty("forge_kotlin_version")}")
    implementation("net.tslat.smartbrainlib:SmartBrainLib-forge-1.20.1:${forgeVersions.getProperty("forge_smartbrainlib_version")}")
    // Immutable release input; the adapter in this module compiles against it.
    modCompileOnly(sbwCoordinate)
}

val modJar by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
tasks.named<Jar>("jar") { archiveClassifier.set("forge") }
artifacts.add(modJar.name, tasks.named("jar"))
