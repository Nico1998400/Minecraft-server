plugins {
    // Auto-provisions the Java 25 toolchain required by Paper 26.x on machines that lack it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nordia"

include("swedencore")
