import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest

// Verifies the pinned SuperbWarfare artifact of the loader being built (SHA-512 against its
// gradle/sbw profile) before anything compiles, and records the effective input in
// build/reports/sbw/input.txt for scripts/verify-release-artifact.py. Applied by every loader module.

@Suppress("UNCHECKED_CAST")
val sbwInput = rootProject.extensions.extraProperties["sbwInput"] as Map<String, String>

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
    dependencies.add(sbwVerification.name, sbwInput.getValue("profile.coordinate"))
}
val verifySbwInput = tasks.register<VerifySbwInput>("verifySbwInput") {
    group = "verification"
    description = "Verify the pinned SBW artifact and record the effective input."
    provenance.set(sbwInput)
    artifacts.from(sbwVerification)
    report.set(rootProject.layout.buildDirectory.file("reports/sbw/input.txt"))
}
tasks.withType<JavaCompile>().configureEach { dependsOn(verifySbwInput) }
tasks.withType<KotlinCompile>().configureEach { dependsOn(verifySbwInput) }
tasks.withType<Jar>().configureEach { dependsOn(verifySbwInput) }
tasks.matching { it.name.startsWith("run") }.configureEach { dependsOn(verifySbwInput) }
