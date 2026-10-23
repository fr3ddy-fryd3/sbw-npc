plugins {
    base
    id("net.neoforged.moddev") version "2.0.80" apply false
    // The forge module applies it; it must share the classpath (and version) with the plugin above.
    id("net.neoforged.moddev.legacyforge") version "2.0.80" apply false
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.BIN
}

version = "${project.property("mod_version")}-mc${project.property("minecraft_version")}"
group = "com.sbwnpc.squad"

// The loader module this build assembles; the others are not configured at all (see settings).
val loader = providers.gradleProperty("loader").orElse("neoforge").get()

val modJar by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(modJar.name, project(mapOf("path" to ":$loader", "configuration" to "modJar")))
}

// Keep the existing setup/deploy entry point pointing at the enabled loader's jar.
val stageModJar = tasks.register<Sync>("stageModJar") {
    from(modJar)
    into(layout.buildDirectory.dir("libs"))
}
tasks.named("assemble") { dependsOn(stageModJar) }
tasks.named("check") { dependsOn(":common:check", ":$loader:check") }
tasks.register("test") { dependsOn(":common:test", ":$loader:test") }

val loaderTasks = if (loader == "neoforge") {
    listOf("testClasses", "verifySbwInput", "runClient", "runServer", "runData", "runGameTestServer", "formatKotlin", "checkKotlinStyle")
} else {
    emptyList()
}
loaderTasks.forEach { name ->
    tasks.register(name) { dependsOn(":$loader:$name") }
}
listOf("buildDevelop", "buildProduction").forEach { name ->
    tasks.register(name) {
        group = "build"
        dependsOn("build")
    }
}
