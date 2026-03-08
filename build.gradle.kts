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

    doLast {
        val sv = serverVersion
        val manifest = groovy.json.JsonSlurper().parseText(manifestFile.readText()) as MutableMap<String, Any>
        manifest["Version"] = project.version.toString()
        manifest["ServerVersion"] = sv

        val json = groovy.json.JsonBuilder(manifest).toPrettyString()
        manifestFile.writeText(json + "\n")
        logger.lifecycle("Updated manifest.json: Version=${project.version}, ServerVersion=$sv")
    }
}

tasks.named("processResources") {
    dependsOn("updatePluginManifest")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("release") {
    dependsOn("build")
    description = "Builds the plugin and creates a GitHub release. Usage: ./gradlew release"

    doLast {
        val tag = "v${project.version}"

        val jarFile = fileTree("build/libs") { include("*.jar") }.singleFile

        exec { commandLine("git", "tag", tag) }
        exec { commandLine("git", "push", "origin", tag) }

        // Wait briefly for GitHub to process the tag and the Actions workflow to create the release
        Thread.sleep(5000)

        exec { commandLine("gh", "release", "upload", tag, jarFile.absolutePath, "--clobber") }

        logger.lifecycle("Release $tag created with ${jarFile.name}")
    }
}
