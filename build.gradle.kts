plugins {
    `maven-publish`
    id("hytale-mod") version "0.+"
}

group = "fr.varyon"
version = "1.0.0"
val javaVersion = 25

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:1.33")
    implementation("org.mongodb:bson:4.11.1")
}

val fatJar = tasks.register<Jar>("fatJar") {
    archiveClassifier.set("")
    archiveBaseName.set("Varyon-World-Inventory")
    archiveVersion.set(version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    val bundled = configurations.runtimeClasspath.get()
        .filter { it.name.contains("gson") || it.name.contains("snakeyaml") || it.name.contains("bson") || it.name.contains("mongodb") }

    from({ bundled.map { zipTree(it) } })

    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = version.toString()
    }
}

tasks.named("build") {
    dependsOn(fatJar)
}

hytale {
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    withSourcesJar()
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "plugin_group"            to findProperty("plugin_group"),
        "plugin_maven_group"      to project.group,
        "plugin_name"             to project.name,
        "plugin_version"          to project.version,
        "server_version"          to findProperty("server_version"),
        "plugin_description"      to findProperty("plugin_description"),
        "plugin_website"          to findProperty("plugin_website"),
        "plugin_main_entrypoint"  to findProperty("plugin_main_entrypoint"),
        "plugin_author"           to findProperty("plugin_author")
    )
    filesMatching("manifest.json") {
        expand(replaceProperties)
    }
    inputs.properties(replaceProperties)
}

publishing {
    repositories {}
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val syncAssets = tasks.register<Copy>("syncAssets") {
    group = "hytale"
    from(layout.buildDirectory.dir("resources/main"))
    into("src/main/resources")
    exclude("manifest.json")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doLast { println("✅ Assets synced from Game to Source Code!") }
}

afterEvaluate {
    val targetTask = tasks.findByName("runServer") ?: tasks.findByName("server")
    if (targetTask != null) {
        targetTask.finalizedBy(syncAssets)
    }
}
