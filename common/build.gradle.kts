plugins {
    id("net.neoforged.moddev")
    id("sbwnpc.jvm-conventions")
}

repositories {
    mavenCentral()
}

neoForge {
    neoFormVersion = project.property("neoform_version") as String
    parchment {
        minecraftVersion = project.property("parchment_minecraft_version") as String
        mappingsVersion = project.property("parchment_mappings_version") as String
    }
}

val commonKotlin by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
artifacts.add(commonKotlin.name, file("src/main/kotlin"))
val commonResources by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
artifacts.add(commonResources.name, file("src/main/resources"))

// Loaders recompile these sources; the vanilla check must not become a second shipped jar.
tasks.named<Jar>("jar") { enabled = false }

val commonSources = fileTree("src/main") {
    include("**/*.kt", "**/*.java")
}
val verifyCommonBoundary = tasks.register("verifyCommonBoundary") {
    group = "verification"
    inputs.files(commonSources)
    doLast {
        val forbidden = Regex(
            """\b(?:net\.(?:neoforged|minecraftforge|fabricmc)|com\.atsuishio\.superbwarfare|net\.minecraft\.client|com\.mojang\.blaze3d)\.""",
        )
        val offenders = commonSources.filter { forbidden.containsMatchIn(it.readText()) }
        check(offenders.isEmpty()) { "Loader, SBW or client references in common: $offenders" }
    }
}
tasks.named("check") {
    dependsOn("classes", verifyCommonBoundary)
}

// Shared sources keep their plain JVM tests here, against vanilla Minecraft only.
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test> { useJUnitPlatform() }
