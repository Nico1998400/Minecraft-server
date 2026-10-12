import java.security.MessageDigest

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
    // Discord bot for /link (optional at runtime: only started when a bot token is configured). Audio is not needed.
    implementation(libs.jda) {
        exclude(module = "opus-java")
    }

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.embedded.postgres)
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Each module keeps its editable config next to its code (essentials/essentials.yml, guide/guide.yml, discord/discord.yml,
// core/config.yml, ...). They are copied flat into the jar root, where the plugin finds them by file name.
tasks.processResources {
    from("src/main/java") {
        include("**/*.yml")
        eachFile { path = name }
        includeEmptyDirs = false
    }
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
    val base = "se.nordia.plugins.libs"
    relocate("com.zaxxer.hikari", "$base.hikari")
    relocate("org.postgresql", "$base.postgresql")
    relocate("net.dv8tion", "$base.jda")
    relocate("okhttp3", "$base.okhttp3")
    relocate("okio", "$base.okio")
    relocate("com.neovisionaries", "$base.nvws")
    relocate("gnu.trove", "$base.trove")
    relocate("org.apache.commons.collections4", "$base.collections4")
    relocate("com.fasterxml.jackson", "$base.jackson")
    mergeServiceFiles()
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
    // The server provides slf4j; bundling a second copy would clash.
    exclude("org/slf4j/**")
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
    // ./gradlew :swedencore:runServer -Pdebug  opens a debug port (5005) so VS Code can swap changed code into the running server.
    if (project.hasProperty("debug")) {
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
    }
    downloadPlugins {
        // FAWE replaces EngineHub WorldEdit; FastAsyncVoxelSniper needs FAWE (not both WorldEdits).
        url("https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/artifact/artifacts/FastAsyncWorldEdit-Paper-2.16.2-SNAPSHOT.jar")
        url("https://github.com/IntellectualSites/fastasyncvoxelsniper/releases/download/3.2.5/fastasyncvoxelsniper-3.2.5.jar")
    }
    doFirst {
        val icon = rootProject.layout.projectDirectory.file("branding/server-icon.png").asFile
        if (icon.isFile) {
            icon.copyTo(layout.projectDirectory.dir("run").file("server-icon.png").asFile, overwrite = true)
        }
    }
    // Pass the gitignored .env (repository root) to the dev server: NORDIA_AI_API_KEY, NORDIA_DB_PASSWORD, ...
    // Variables already set in the real environment win over the file.
    doFirst {
        val envFile = rootProject.layout.projectDirectory.file(".env").asFile
        if (envFile.isFile) {
            envFile.readLines(Charsets.UTF_8).map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .forEach { line ->
                    val key = line.substringBefore("=").trim().removePrefix("export ").trim()
                    val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    if (key.isNotEmpty() && value.isNotEmpty() && System.getenv(key) == null) {
                        environment(key, value)
                    }
                }
        }
    }
}

// Builds the optional NORDIA resource pack (quest emblems) and prints its SHA-1 for texture-pack.sha1 in config.yml.
tasks.register<Zip>("buildResourcePack") {
    group = "nordia"
    description = "Zips resourcepack/ into build/NORDIA-pack.zip"
    from("resourcepack") {
        include("pack.mcmeta", "assets/**")
    }
    destinationDirectory = layout.buildDirectory
    archiveFileName = "NORDIA-pack.zip"
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    doLast {
        val bytes = archiveFile.get().asFile.readBytes()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        println("NORDIA-pack.zip SHA-1: $sha1")
    }
}

// The resource pack is bundled into the jar (as nordia-pack.zip) so the plugin can hand it out itself.
tasks.processResources {
    from(tasks.named("buildResourcePack")) {
        rename { "nordia-pack.zip" }
    }
}
