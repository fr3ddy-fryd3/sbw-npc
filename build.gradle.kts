plugins {
    idea
    id("net.neoforged.moddev") version "2.0.80"
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.BIN
}

version = "${project.property("mod_version")}-mc${project.property("minecraft_version")}"
group = "com.sbwnpc.squad"

repositories {
    mavenLocal()
    mavenCentral()
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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
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
                file("src/main/resources/").absolutePath
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

sourceSets.main.get().resources {
    srcDir("src/generated/resources")
}

// Bakes DebugFlags' startup default into the jar at build time, so a locally-built dev jar comes
// up with verbose trace logging/markers on without touching runtime code, while a plain/production
// build stays quiet by default. `./gradlew buildDevelop` flips it on; plain `build` (and the
// explicit `buildProduction` alias) leave it off.
val generateBuildFlags = tasks.register("generateBuildFlags") {
    val debugDefault = gradle.startParameter.taskNames.any {
        it.equals("buildDevelop", ignoreCase = true) || it.equals("runGameTestServer", ignoreCase = true)
    }
    val outputDir = layout.buildDirectory.dir("generated/buildFlags/kotlin")
    inputs.property("debugDefault", debugDefault)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().dir("com/sbwnpc/squad/combat").asFile.apply { mkdirs() }
            .resolve("BuildFlags.kt")
        file.writeText(
            """
            package com.sbwnpc.squad.combat

            // Generated by the `generateBuildFlags` Gradle task — do not edit by hand.
            internal object BuildFlags {
                const val DEBUG_ENABLED: Boolean = $debugDefault
            }
            """.trimIndent() + "\n"
        )
    }
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

val gameRuntimeOnly: Configuration by configurations.creating
configurations.named("runtimeClasspath") { extendsFrom(gameRuntimeOnly) }

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${project.property("kotlinforforge_version")}")

    // Резолвится через includeBuild("SuperbWarfare") + dependencySubstitution в settings.gradle.kts —
    // указанная здесь версия ни на что не влияет (Gradle подставит локальный проект).
    compileOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}")
    gameRuntimeOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}")
    // SBW's published runtime variant also lists its optional development modpack. Its jar already
    // embeds the required libraries; headless tests need that jar, not the client test modpack.
    add(gameTest.runtimeOnlyConfigurationName, "com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}") {
        isTransitive = false
    }
    // Regression tests exercise SBW's resource decoder alongside our static model loader.
    testCompileOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}") {
        isTransitive = false
    }
    testRuntimeOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}") {
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

val kotlinStyleFiles = fileTree("src") {
    include("main/kotlin/**/*.kt", "test/kotlin/**/*.kt", "gameTest/kotlin/**/*.kt")
}

val checkKotlinStyle = tasks.register<JavaExec>("checkKotlinStyle") {
    group = "verification"
    description = "Check Kotlin source formatting using the project's .editorconfig."
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    inputs.files(kotlinStyleFiles, ".editorconfig")
    args("--relative", "src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt", "src/gameTest/kotlin/**/*.kt")
}

tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Format this project's Kotlin sources; excludes generated code and SuperbWarfare."
    classpath = kotlinStyle
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    args("--format", "--relative", "src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt", "src/gameTest/kotlin/**/*.kt")
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
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir(generateBuildFlags)
    }
}
