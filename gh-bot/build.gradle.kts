plugins {
    java
}

group = "dev.ghbot"
version = "0.21.40"
description = "GH-Bot — AI builder & editing agent for PaperMC (GH000, GH001, ...)"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API — built against 1.21.11, verified to run on the 1.21.x line and 26.x.
    // Gson is excluded: it is blocked on some mirrors and we avoid the dep entirely
    // (session persistence uses Bukkit's built-in YamlConfiguration).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") {
        exclude(group = "com.google.gson", module = "gson")
    }
}

tasks {
    compileJava {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveBaseName.set("GHBot")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
    }
}
