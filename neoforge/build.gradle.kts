import java.security.MessageDigest

plugins {
    idea
    id("net.neoforged.moddev")
    id("sbwnpc.jvm-conventions")
}

version = rootProject.version
group = "com.sbwnpc.squad"

@Suppress("UNCHECKED_CAST")
val sbwInput = rootProject.extensions.extraProperties["sbwInput"] as Map<String, String>
val sbwCoordinate = sbwInput.getValue("profile.coordinate")

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Pinned SBW"
        url = uri("https://api.modrinth.com/maven")
        content { includeModule("maven.modrinth", "Cd3DYqzn") }
    }
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }
    maven {
        // GeckoLib — compile-time only, and only because Kotlin needs the full supertype chain of
        // SBW's Geo*Entity classes (DroneEntity extends GeoVehicleEntity implements GeoEntity) to
        // resolve members declared on them. Same version SBW jar-in-jars, never shipped by us.
        name = "GeckoLib (Cloudsmith)"
        url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
        content { includeGroup("software.bernie.geckolib") }
    }
    maven {
        // SmartBrainLib's own publishing target (verified by resolving the actual jar +
        // maven-metadata.xml from this URL, not guessed) — MPL-2.0, modId "smartbrainlib".
        name = "SmartBrainLib (Cloudsmith)"
        url = uri("https://dl.cloudsmith.io/public/tslat/sbl/maven/")
        content { includeGroup("net.tslat.smartbrainlib") }
    }
    maven {
        // JourneyMap API — compile-time only. Its licence allows building against it but not
        // shipping its classes, and JourneyMap itself brings them at runtime.
        name = "BlameJared"
        url = uri("https://maven.blamejared.com")
        content { includeGroup("info.journeymap") }
    }
    maven {
        name = "SimpleBedrockModel"
        url = uri("https://jitpack.io")
        content { includeGroup("com.github.MCModderAnchor") }
    }
}

base {
    archivesName.set(project.property("mod_id") as String)
}

// Real server/world tests have their own sources and are never packaged in the mod jar.
val gameTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
// Custom tests use the same internal access as JUnit, without exposing implementation in the mod API.
kotlin.target.compilations.getByName("gameTest").associateWith(kotlin.target.compilations.getByName("main"))
val gameTestReportName = providers.gradleProperty("gameTestReportName").orElse("latest").get()
require(gameTestReportName.matches(Regex("[A-Za-z0-9_-]+"))) { "gameTestReportName must be a simple directory name" }
configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[gameTest.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

neoForge {
    version = project.property("neo_version") as String

    parchment {
        mappingsVersion = project.property("parchment_mappings_version") as String
        minecraftVersion = project.property("parchment_minecraft_version") as String
    }

    mods {
        create(project.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
        create("sbwnpcGameTest") {
            sourceSet(sourceSets.main.get())
            sourceSet(gameTest)
        }
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
        }

        create("gameTestServer") {
            type = "gameTestServer"
            sourceSet = gameTest
            gameDirectory = layout.buildDirectory.dir("gametest/server").get().asFile
            systemProperty("neoforge.enabledGameTestNamespaces", project.property("mod_id") as String)
            systemProperty("sbwnpc.gameTestReports", layout.buildDirectory.dir("reports/gameTest/$gameTestReportName").get().asFile.absolutePath)
            systemProperty("sbwnpc.gameTestNegativeControl", providers.gradleProperty("gameTestNegativeControl").orElse("false").get())
            systemProperty("sbwnpc.gameTestFilter", providers.gradleProperty("gameTestFilter").orElse("").get())
            systemProperty("sbwnpc.gameTestReplay", providers.gradleProperty("gameTestReplay").orElse("").get())
            systemProperty("sbwnpc.gameTestBattleSaves", providers.gradleProperty("gameTestBattleSaves").orElse("").get())
            systemProperty("sbwnpc.gameTestBattles", providers.gradleProperty("gameTestBattles").orElse("").get())
            systemProperty("sbwnpc.gameTestWatchNpc", providers.gradleProperty("gameTestWatchNpc").orElse("f8104e12-9378-4094-8cc7-54145ce8df70").get())
            logLevel = org.slf4j.event.Level.INFO
        }

        create("data") {
            data()
            programArguments.addAll(
                "--mod",
                project.property("mod_id") as String,
                "--all",
                "--output",
                file("src/generated/resources/").absolutePath,
                "--existing",
                file("src/main/resources/").absolutePath,
                "--existing",
                rootProject.layout.projectDirectory.dir("common/src/main/resources").asFile.absolutePath
            )
        }

        configureEach {
            // The combined main + GameTest mod is selected only for the headless run.
            loadedMods.set(listOf(mods.getByName(if (name == "gameTestServer") "sbwnpcGameTest" else project.property("mod_id") as String)))
            jvmArguments = listOf(
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+AllowEnhancedClassRedefinition"
            )
            systemProperty("forge.logging.markers", "REGISTRIES")
            if (name != "gameTestServer") logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    addModdingDependenciesTo(gameTest)

    unitTest {
        enable()
        testedMod.set(mods.named(project.property("mod_id") as String))
        loadedMods.set(listOf(mods.getByName(project.property("mod_id") as String)))
    }
}

val gameTestStartMarker = layout.buildDirectory.file("gametest/server/run-started-at.txt")
val archiveGameTestLog = tasks.register("archiveGameTestLog") {
    // A Copy task owns its destination directory; Gradle's stale-output cleanup can delete the
    // CSV files written there by the server. Copy just the log without declaring that ownership.
    doLast {
        val marker = gameTestStartMarker.get().asFile
        check(marker.exists()) { "Missing GameTest start marker" }
        val startedAt = marker.readText().toLong()
        val log = layout.buildDirectory.file("gametest/server/logs/latest.log").get().asFile
        check(log.exists() && log.lastModified() >= startedAt) { "GameTest server produced no fresh log" }
        val reports = layout.buildDirectory.dir("reports/gameTest/$gameTestReportName").get().asFile
        reports.mkdirs()
        log.copyTo(reports.resolve("server.log"), overwrite = true)
        check(log.readText().contains("GAME TESTS COMPLETE")) { "GameTest server stopped before completing tests; see ${reports.resolve("server.log")}" }
    }
}
tasks.named("runGameTestServer") {
    doFirst {
        val marker = gameTestStartMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText(System.currentTimeMillis().toString())
    }
    finalizedBy(archiveGameTestLog)
}


tasks.register("buildDevelop") {
    group = "build"
    description = "Assemble the mod jar with DebugFlags (trace logging + debug particle markers) defaulted ON."
    dependsOn("build")
}

tasks.register("buildProduction") {
    group = "build"
    description = "Assemble the mod jar with DebugFlags defaulted OFF — same as plain 'build', named explicitly for release builds."
    dependsOn("build")
}

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

sourceSets.main.get().resources {
    srcDir("src/generated/resources")
    srcDir(commonResources)
}

val gameRuntimeOnly: Configuration by configurations.creating
configurations.named("runtimeClasspath") { extendsFrom(gameRuntimeOnly) }

dependencies {
    add(commonKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonKotlin")))
    add(commonResources.name, project(mapOf("path" to ":common", "configuration" to "commonResources")))
    add(commonGeneratedKotlin.name, project(mapOf("path" to ":common", "configuration" to "commonGeneratedKotlin")))

    implementation("thedarkcolour:kotlinforforge-neoforge:${project.property("kotlinforforge_version")}")

    // Immutable release input; local source substitution requires -PsbwSourceDir.
    compileOnly(sbwCoordinate)
    gameRuntimeOnly(sbwCoordinate)
    // SBW's published runtime variant also lists its optional development modpack. Its jar already
    // embeds the required libraries; headless tests need that jar, not the client test modpack.
    add(gameTest.runtimeOnlyConfigurationName, sbwCoordinate) {
        isTransitive = false
    }
    // Regression tests exercise SBW's resource decoder alongside our static model loader.
    testCompileOnly(sbwCoordinate) {
        isTransitive = false
    }
    testRuntimeOnly(sbwCoordinate) {
        isTransitive = false
    }

    implementation("net.tslat.smartbrainlib:SmartBrainLib-neoforge-1.21.1:${project.property("smartbrainlib_version")}")
    // See the GeckoLib repository comment above. SBW bundles 4.7.5 (jijImplement in its build).
    compileOnly("software.bernie.geckolib:geckolib-neoforge-1.21.1:4.7.5")
    // Already bundled by SBW. Used to bake NPC guns into static geometry, without GeoItemRenderer.
    compileOnly("com.github.MCModderAnchor:SimpleBedrockModel:2.5.1.1-neoforge-mc1.21.1")
    testCompileOnly("com.github.MCModderAnchor:SimpleBedrockModel:2.5.1.1-neoforge-mc1.21.1")
    // Optional map integration (see integration/journeymap); never bundled.
    compileOnly("info.journeymap:journeymap-api-neoforge:${project.property("journeymap_api_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // NeoForge runs tests from its run directory; source-scanning tests need the real root.
    systemProperty("sbwnpc.projectDir", projectDir.absolutePath)
}

// Development tooling has its own classpath and is never included in the mod's runtime or jar.
val kotlinStyle: Configuration by configurations.creating {
    isCanBeConsumed = false
}
dependencies {
    kotlinStyle("com.pinterest.ktlint:ktlint-cli:1.8.0:all") { isTransitive = false }
}

// One style for every module's Kotlin, checked from the root so the shared .editorconfig applies.
val kotlinStylePatterns = listOf(
    "common/src/main/kotlin/**/*.kt",
    "common/src/test/kotlin/**/*.kt",
    "neoforge/src/main/kotlin/**/*.kt",
    "neoforge/src/test/kotlin/**/*.kt",
    "neoforge/src/gameTest/kotlin/**/*.kt",
)
val kotlinStyleFiles = rootProject.fileTree(rootDir) {
    include(kotlinStylePatterns)
}

val checkKotlinStyle = tasks.register<JavaExec>("checkKotlinStyle") {
    group = "verification"
    description = "Check Kotlin source formatting using the project's .editorconfig."
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    inputs.files(kotlinStyleFiles, rootProject.file(".editorconfig"))
    args(listOf("--relative") + kotlinStylePatterns)
}

tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Format the project's Kotlin sources; excludes generated code and SuperbWarfare."
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(listOf("--format", "--relative") + kotlinStylePatterns)
}

tasks.named("check") { dependsOn(checkKotlinStyle) }

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(commonKotlin)
        kotlin.srcDir(commonGeneratedKotlin)
    }
}

/** Verifies the selected dependency and records its effective inputs for build audits. */
abstract class VerifySbwInput : DefaultTask() {
    @get:Input abstract val provenance: MapProperty<String, String>
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val details = provenance.get().toMutableMap()
        if (details.getValue("mode") == "published") {
            val artifact = artifacts.singleFile
            val digest = MessageDigest.getInstance("SHA-512")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count != -1) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == details.getValue("profile.sha512")) {
                "SBW SHA-512 mismatch: expected ${details["profile.sha512"]}, got $actual"
            }
            details["artifact.sha512"] = actual
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(details.toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        }
    }
}

val sbwVerification by configurations.creating { isTransitive = false }
if (sbwInput.getValue("mode") == "published") {
    dependencies.add(sbwVerification.name, sbwCoordinate)
}
val verifySbwInput = tasks.register<VerifySbwInput>("verifySbwInput") {
    group = "verification"
    description = "Verify the pinned SBW artifact and record the effective input."
    provenance.set(sbwInput)
    artifacts.from(sbwVerification)
    report.set(rootProject.layout.buildDirectory.file("reports/sbw/input.txt"))
}
tasks.withType<JavaCompile>().configureEach { dependsOn(verifySbwInput) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(verifySbwInput) }
// Resource regression tests must read the same SBW input as the compiled addon.
val sbwTestResources = layout.buildDirectory.dir("sbw-test-resources")
val prepareSbwTestResources = tasks.register<Sync>("prepareSbwTestResources") {
    dependsOn(verifySbwInput)
    from(provider {
        if (sbwInput.getValue("mode") == "published") zipTree(sbwVerification.singleFile)
        else file(sbwInput.getValue("source.path")).resolve("src/main/resources")
    })
    include("assets/**")
    into(sbwTestResources)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareSbwTestResources)
    inputs.dir(sbwTestResources).withPropertyName("sbwResources")
    systemProperty("sbwnpc.sbwResources", sbwTestResources.get().asFile.absolutePath)
}
tasks.withType<Jar>().configureEach { dependsOn(verifySbwInput) }
tasks.matching { it.name.startsWith("run") }.configureEach { dependsOn(verifySbwInput) }

tasks.named<Jar>("jar") { archiveClassifier.set("neoforge") }
val modJar by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
artifacts.add(modJar.name, tasks.named<Jar>("jar"))
