val loader = providers.gradleProperty("loader").orElse("neoforge").get()
require(loader in setOf("neoforge", "fabric", "forge")) { "Unknown loader: $loader" }
val profileFile = settingsDir.resolve("gradle/sbw/$loader.properties")
require(profileFile.isFile) { "Missing SBW profile: $profileFile" }
val profile = java.util.Properties().apply { profileFile.inputStream().use { load(it) } }
require(profile.getProperty("loader") == loader) { "SBW profile loader mismatch" }
require(loader == "neoforge" && profile.getProperty("status") == "enabled") {
    "SBW $loader profile is ${profile.getProperty("status")}; see #15/#16 and docs/SBW-COMPATIBILITY.md"
}
require(profile.getProperty("minecraft") == providers.gradleProperty("minecraft_version").get()) {
    "SBW profile Minecraft version mismatch"
}
require(profile.getProperty("version") == providers.gradleProperty("superbwarfare_version").get()) {
    "superbwarfare_version must match the selected pinned profile"
}
val provenance = profile.stringPropertyNames().associate {
    "profile.$it" to profile.getProperty(it)
}.toMutableMap()
val sourceOverride = providers.gradleProperty("sbwSourceDir").orNull
provenance["mode"] = if (sourceOverride == null) "published" else "local-source"
if (sourceOverride != null) {
    require(providers.environmentVariable("CI").orElse("").get() in listOf("", "false")) {
        "Local SBW source overrides are development-only and disabled in CI"
    }
    val source = settingsDir.resolve(sourceOverride).canonicalFile
    require(source.isDirectory) { "Missing SBW source override: $source" }
    fun git(vararg args: String) = providers.exec {
        commandLine(listOf("git", "-C", source.path) + args)
    }.standardOutput.asText.get().trim()
    require(file(git("rev-parse", "--show-toplevel")).canonicalFile == source) {
        "SBW override must be a Git checkout root"
    }
    val sourceProperties = java.util.Properties().apply {
        source.resolve("gradle.properties").inputStream().use { load(it) }
    }
    require(sourceProperties.getProperty("mod_id") == "superbwarfare" &&
        sourceProperties.getProperty("minecraft_version") == profile.getProperty("minecraft") &&
        source.resolve("build.gradle.kts").readText().contains("net.neoforged.moddev")) {
        "SBW override must be a NeoForge SuperbWarfare checkout for Minecraft 1.21.1"
    }
    provenance["source.path"] = source.path
    provenance["source.revision"] = git("rev-parse", "HEAD")
    provenance["source.dirty"] = git("status", "--porcelain", "--untracked-files=all").isNotEmpty().toString()
    provenance["source.repository"] = git("remote", "get-url", "origin")
        .substringAfter("://").substringAfterLast("@").replace("github.com:", "github.com/")
        .substringBefore("?").substringBefore("#").removeSuffix(".git")
    provenance["source.version"] = sourceProperties.getProperty("mod_version", "unknown")
    logger.warn("Using development SBW override: ${provenance.filterKeys { it.startsWith("source.") }}")
    includeBuild(source) {
        dependencySubstitution {
            substitute(module(profile.getProperty("coordinate"))).using(project(":"))
        }
    }
}
val recordedInput = provenance.toMap()
logger.lifecycle("SBW input: loader=$loader mode=${recordedInput["mode"]} coordinate=${profile.getProperty("coordinate")}")
gradle.beforeProject(object : Action<Project> {
    override fun execute(project: Project) {
        if (project.path == ":") project.extensions.extraProperties["sbwInput"] = recordedInput
    }
})
