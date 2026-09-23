plugins {
    java
}

group = "dev.ghbot"
version = "0.25.0"
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
        // v0.22.4 — the expand map alone does NOT reliably invalidate Gradle's
        // up-to-date/build-cache for this task: v0.22.3 shipped with a stale
        // '0.22.2' plugin.yml stamp (jar re-assembled new code over cached
        // resources after the version bump → `version GHBot` lied in prod).
        // Reproduced in-lab on Gradle 8.10.2 (jar named 0.22.5-TEST carried a
        // 0.22.4 stamp until this line forced re-stamping).
        inputs.property("version", project.version)
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
