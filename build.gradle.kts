import java.util.jar.JarFile
import java.time.ZonedDateTime
import java.time.ZoneOffset

plugins {
    id("java")
}

val patchline: String by project
val hytaleHome: String = "${System.getProperty("user.home")}/AppData/Roaming/Hytale"
val hytaleServerJar: String = (project.findProperty("hytaleServerJar") as String?)
    ?: "$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"

// Extract server version from HytaleServer.jar's MANIFEST.MF lazily (allows CI builds without local install)
val serverVersion: String by lazy {
    val jarFile = file(hytaleServerJar)
    if (jarFile.exists()) {
        JarFile(jarFile).use { jar ->
            jar.manifest.mainAttributes.getValue("Implementation-Version")
                ?: error("Could not read Implementation-Version from HytaleServer.jar manifest")
        }
    } else {
        logger.warn("HytaleServer.jar not found at $hytaleServerJar — using 'unknown' for server version")
        "unknown"
    }
}

group = "com.msgames"
val buildDate = ZonedDateTime.now(ZoneOffset.UTC)
version = String.format("%d.%d.%d-%d", buildDate.year, buildDate.monthValue, buildDate.dayOfMonth, buildDate.toLocalTime().toSecondOfDay())

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(project.findProperty("java_version") as String)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files(hytaleServerJar))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Automatically update manifest.json with current version and server version before build
tasks.register("updatePluginManifest") {
    val manifestFile = file("src/main/resources/manifest.json")
    val pluginVersion = project.version.toString()

    doLast {
        val sv = serverVersion
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parseText(manifestFile.readText()) as MutableMap<String, Any>
        manifest["Version"] = pluginVersion
        manifest["ServerVersion"] = sv

        val json = groovy.json.JsonBuilder(manifest).toPrettyString()
        manifestFile.writeText(json + "\n")
        logger.lifecycle("Updated manifest.json: Version=$pluginVersion, ServerVersion=$sv")
    }
}

tasks.named("processResources") {
    dependsOn("updatePluginManifest")
}

tasks.named("build") { mustRunAfter("clean") }

tasks.test {
    useJUnitPlatform()
}

tasks.register("release") {
    group = "build"
    dependsOn("clean", "build")
    description = "Builds the plugin and creates a GitHub release. Usage: ./gradlew release [-Ppatchline=beta]"

    val tag = "v${project.version}"
    val projectDir = project.projectDir
    val releasePatchline = patchline
    val releaseServerVersion = serverVersion

    doLast {
        for (cmd in listOf("git", "gh")) {
            try {
                ProcessBuilder(cmd, "--version").start().waitFor()
            } catch (_: Exception) {
                error("'$cmd' is not available on PATH. Install it and run this task from a terminal.")
            }
        }

        val jarFile = fileTree("build/libs") { include("*.jar") }.singleFile

        fun run(vararg args: String) {
            val process = ProcessBuilder(*args)
                .directory(projectDir)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) error("Command failed (exit $exitCode): ${args.joinToString(" ")}")
        }

        // Get the current commit hash
        val commitProcess = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(projectDir)
            .start()
        val commitHash = commitProcess.inputStream.bufferedReader().readText().trim()
        commitProcess.waitFor()

        val releaseNotes = """
            ## Build Info
            - **Patchline:** $releasePatchline
            - **Server Version:** $releaseServerVersion
            - **Commit:** $commitHash
        """.trimIndent()

        run("git", "tag", tag)
        run("git", "push", "origin", tag)

        run("gh", "release", "create", tag,
            "\"${jarFile.absolutePath}\"",
            "--title", tag,
            "--notes", releaseNotes,
            "--generate-notes")

        logger.lifecycle("Release $tag created with ${jarFile.name}")
    }
}
