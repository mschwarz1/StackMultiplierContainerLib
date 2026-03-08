import java.util.jar.JarFile
import java.time.ZonedDateTime
import java.time.ZoneOffset

plugins {
    id("java")
}

val patchline: String by project
val hytaleHome: String = "${System.getProperty("user.home")}/AppData/Roaming/Hytale"
val hytaleServerJar: String = "$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"

// Extract server version from HytaleServer.jar's MANIFEST.MF at configuration time
val serverVersion: String = JarFile(file(hytaleServerJar)).use { jar ->
    jar.manifest.mainAttributes.getValue("Implementation-Version")
        ?: error("Could not read Implementation-Version from HytaleServer.jar manifest")
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
