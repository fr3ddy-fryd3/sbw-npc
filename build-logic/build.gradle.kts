plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
}

kotlin {
    jvmToolchain(17)
}
