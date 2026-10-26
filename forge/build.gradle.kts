import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
        content {
            // SuperbWarfare, and GeckoLib, whose types are in its signatures.
            includeModule("maven.modrinth", "Cd3DYqzn")
            includeModule("maven.modrinth", "8BmcQJ2H")
        }
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
    // Kotlin for Forge 4.11 ships the Kotlin 2.0 runtime, which the mod runs on: keep to its language and library.
    languageVersion.set(KotlinVersion.KOTLIN_2_0)
    apiVersion.set(KotlinVersion.KOTLIN_2_0)
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

// The shared data uses 1.21 folder names (loot_table); 1.20.1 reads loot_tables, from this module's own copy.
tasks.processResources {
    exclude("data/sbwnpc/loot_table/**")
}

// The mixins are written on Mojang names; the processor records their SRG equivalents in a refmap.
mixin {
    add(sourceSets.main.get(), "sbwnpc.forge.refmap.json")
    config("sbwnpc.forge.mixins.json")
}

// The shared sources are recompiled here, as the other loader modules do. They do not compile
// against 1.20.1 yet: see docs/LOADER-PORTS.md for the 441 errors this module starts from.
val commonKotlin by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val commonClientKotlin by configurations.creating {
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
        kotlin.srcDir(commonClientKotlin)
        kotlin.srcDir(commonGeneratedKotlin)
    }
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
    compileOnly("org.spongepowered:mixin:0.8.5")
    add(commonKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonKotlin")))
    add(commonClientKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonClientKotlin")))
    add(commonResources.name, project(mapOf("path" to ":common", "configuration" to "commonResources")))
    add(commonGeneratedKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonGeneratedKotlin")))
    add(commonJava.name, project(mapOf("path" to ":common", "configuration" to "commonJava")))

    implementation("thedarkcolour:kotlinforforge:${forgeVersions.getProperty("forge_kotlin_version")}")
    modImplementation("net.tslat.smartbrainlib:SmartBrainLib-forge-1.20.1:${forgeVersions.getProperty("forge_smartbrainlib_version")}")
    // Immutable release input; the adapter in this module compiles against it.
    modCompileOnly(sbwCoordinate)
    // The GeckoLib SBW bundles (4.4.6): its types are in the drone signatures.
    modCompileOnly("maven.modrinth:8BmcQJ2H:iS7jylhp")
}

val modJar by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
tasks.named<Jar>("jar") {
    archiveClassifier.set("forge")
    // Forge 1.20.1 reads mixin configs from the jar manifest; the [[mixins]] table in mods.toml is NeoForge's and is ignored.
    manifest.attributes("MixinConfigs" to "sbwnpc.forge.mixins.json")
}
artifacts.add(modJar.name, tasks.named("jar"))
