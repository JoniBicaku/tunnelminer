plugins {
    id("fabric-loom") version "1.12.+"
    id("maven-publish")
}

version = "1.0.0"
group = "com.tunnelminer"

base {
    archivesName.set("tunnelminer")
}

repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
}

dependencies {
    // Exact mappings for Minecraft 1.21.10
    minecraft("com.mojang:minecraft:1.21.10")
    mappings("net.fabricmc:yarn:1.21.10+build.2:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.9")

    // Meteor Client 1.21.10
    modImplementation("meteordevelopment:meteor-client:1.21.10-SNAPSHOT") {
        isTransitive = false
    }

    // Orbit event bus - needed at compile time for @EventHandler
    include(implementation("meteordevelopment:orbit:0.2.4")!!)
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }

    jar {
        from("LICENSE")
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}