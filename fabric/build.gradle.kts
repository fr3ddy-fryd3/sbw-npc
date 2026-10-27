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
        content {
            // ReFabricated, and the mods its own API exposes in signatures.
            listOf("XU8zWdjt", "8BmcQJ2H", "ohNO6lps", "5aaWibi9", "K01OU20C", "9s6osm5g").forEach { includeModule("maven.modrinth", it) }
        }
    }
    maven {
        // SimpleBedrockModel for Fabric: ReFabricated bundles it at runtime; the static gun meshes compile against it.
        name = "SimpleBedrockModel-Fabric"
        url = uri("https://jitpack.io")
        content { includeGroup("com.github.Sh1roCu") }
    }
    maven {
        name = "SmartBrainLib (Cloudsmith)"
        url = uri("https://dl.cloudsmith.io/public/tslat/sbl/maven/")
        content { includeGroup("net.tslat.smartbrainlib") }
    }
    maven {
        // JourneyMap API: compile-time only; JourneyMap brings its classes at runtime.
        name = "BlameJared"
        url = uri("https://maven.blamejared.com")
        content { includeGroup("info.journeymap") }
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
val commonClientKotlin by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val commonMc121Kotlin by configurations.creating {
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
        kotlin.srcDir(commonMc121Kotlin)
        kotlin.srcDir(commonGeneratedKotlin)
    }
}

dependencies {
    add(commonKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonKotlin")))
    add(commonClientKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonClientKotlin")))
    add(commonMc121Kotlin.name, project(mapOf("path" to ":common", "configuration" to "commonMc121Kotlin")))
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
    // Types in ReFabricated's signatures: GeckoLib (drones), the config port (explosion settings and
    // our own config), Trinkets and Cardinal Components (player data), Cloth Config. Versions as in the local pack.
    listOf("8BmcQJ2H:tmxM7U4W", "ohNO6lps:N5qzq0XV", "5aaWibi9:JagCscwi", "K01OU20C:nLsCe2VD", "9s6osm5g:HpMb5wGb")
        .forEach { modCompileOnly("maven.modrinth:$it") }
    // The revision ReFabricated bundles (its gradle.properties: simple_bedrock_model_version).
    // Not transitive: its pom lists mods the addon neither compiles against nor can resolve.
    modCompileOnly("com.github.Sh1roCu:SimpleBedrockModel-Fabric:de0caee7f9656676e6de0859077fb61103007a9e") { isTransitive = false }
    modCompileOnly("info.journeymap:journeymap-api-fabric:${project.property("journeymap_api_version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.withType<Test> { useJUnitPlatform() }

// fabric.mod.json holds ${version}; left as is, Loader reads that literal as the mod's version.
tasks.processResources {
    val modVersion = project.property("mod_version") as String
    inputs.property("modVersion", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

val modJar by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
// Named by loader like the NeoForge jar (sbwnpc-<version>-mc<mc>-fabric.jar), so jars of different loaders never share a name.
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") { archiveClassifier.set("fabric") }
artifacts.add(modJar.name, tasks.named("remapJar"))
