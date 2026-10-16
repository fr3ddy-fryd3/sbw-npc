plugins {
    base
    id("net.neoforged.moddev") version "2.0.80" apply false
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.BIN
}

version = "${project.property("mod_version")}-mc${project.property("minecraft_version")}"
group = "com.sbwnpc.squad"

val modJar by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(modJar.name, project(mapOf("path" to ":neoforge", "configuration" to "modJar")))
}

// Keep the existing setup/deploy entry point pointing at the enabled loader's jar.
val stageNeoForgeJar = tasks.register<Sync>("stageNeoForgeJar") {
    from(modJar)
    into(layout.buildDirectory.dir("libs"))
}
tasks.named("assemble") { dependsOn(stageNeoForgeJar) }
tasks.named("check") { dependsOn(":common:check", ":neoforge:check") }
tasks.register("test") { dependsOn(":common:test", ":neoforge:test") }

listOf("testClasses", "verifySbwInput", "runClient", "runServer", "runData", "runGameTestServer", "formatKotlin", "checkKotlinStyle").forEach { name ->
    tasks.register(name) { dependsOn(":neoforge:$name") }
}
listOf("buildDevelop", "buildProduction").forEach { name ->
    tasks.register(name) {
        group = "build"
        dependsOn("build")
    }
}
