plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "SwedenCore — the central NORDIA plugin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)

    implementation(libs.hikari)
    implementation(libs.postgresql)

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.embedded.postgres)
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.shadowJar {
    archiveBaseName = "SwedenCore"
    archiveClassifier = ""
    // Relocate bundled libraries so they never clash with other plugins on the server.
    val base = "se.nordia.swedencore.libs"
    relocate("com.zaxxer.hikari", "$base.hikari")
    relocate("org.postgresql", "$base.postgresql")
    mergeServiceFiles()
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
    jvmArgs("-Xms8G", "-Xmx8G", "-Dcom.mojang.eula.agree=true", "-Djava.net.preferIPv4Stack=true")
    downloadPlugins {
        // FAWE replaces EngineHub WorldEdit; FastAsyncVoxelSniper needs FAWE (not both WorldEdits).
        url("https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/artifact/artifacts/FastAsyncWorldEdit-Paper-2.15.5-SNAPSHOT.jar")
        url("https://github.com/IntellectualSites/fastasyncvoxelsniper/releases/download/3.2.5/fastasyncvoxelsniper-3.2.5.jar")
    }
    doFirst {
        val icon = rootProject.layout.projectDirectory.file("branding/server-icon.png").asFile
        if (icon.isFile) {
            icon.copyTo(layout.projectDirectory.dir("run").file("server-icon.png").asFile, overwrite = true)
        }
    }
}
