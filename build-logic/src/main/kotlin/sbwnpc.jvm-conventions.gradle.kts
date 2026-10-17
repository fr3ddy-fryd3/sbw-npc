import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
}

// Code-smell checks only: formatting belongs to ktlint (checkKotlinStyle), so detekt-formatting
// is deliberately not added. Each module keeps its own baseline of findings that predate detekt.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    // The whole src tree, so source sets beyond main and test (neoforge's gameTest) are checked too.
    source.setFrom(file("src"))
}

// detekt skips itself as NO-SOURCE when its sources are empty, so a module whose code moved away
// would pass with nothing analysed. This runs first and fails instead.
val detektSources = files("src").asFileTree.matching { include("**/*.kt") }
val verifyDetektSources = tasks.register("verifyDetektSources") {
    val modulePath = project.path
    doLast { check(!detektSources.isEmpty) { "detekt found no Kotlin sources in $modulePath" } }
}
tasks.named<Detekt>("detekt") { dependsOn(verifyDetektSources) }
