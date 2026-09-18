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
}

base {
    archivesName.set(project.property("mod_id") as String)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

neoForge {
    version = project.property("neo_version") as String

    parchment {
        mappingsVersion = project.property("parchment_mappings_version") as String
        minecraftVersion = project.property("parchment_minecraft_version") as String
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
            jvmArguments = listOf(
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+AllowEnhancedClassRedefinition"
            )
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        create(project.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }

    unitTest {
        enable()
        testedMod.set(mods.named(project.property("mod_id") as String))
    }
}

sourceSets.main.get().resources {
    srcDir("src/generated/resources")
}

val gameRuntimeOnly: Configuration by configurations.creating
configurations.named("runtimeClasspath") { extendsFrom(gameRuntimeOnly) }

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${project.property("kotlinforforge_version")}")

    // Резолвится через includeBuild("SuperbWarfare") + dependencySubstitution в settings.gradle.kts —
    // указанная здесь версия ни на что не влияет (Gradle подставит локальный проект).
    compileOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}")
    gameRuntimeOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}")
    testRuntimeOnly("com.atsuishio.superbwarfare:superbwarfare:${project.property("superbwarfare_version")}") {
        isTransitive = false
    }

    implementation("net.tslat.smartbrainlib:SmartBrainLib-neoforge-1.21.1:${project.property("smartbrainlib_version")}")
    // See the GeckoLib repository comment above. SBW bundles 4.7.5 (jijImplement in its build).
    compileOnly("software.bernie.geckolib:geckolib-neoforge-1.21.1:4.7.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

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
}
