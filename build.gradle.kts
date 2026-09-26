plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    mavenCentral()
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)

    // Tests (Gradle 9 exige declarar el launcher)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// In-game bench: Fabric client gametests in their own source set. Fabric API is on the gametest
// classpath only, so the shipped jar does not change. Run with ./gradlew runClientGameTest (a window opens).
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "xploits-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    "modGametestImplementation"("net.fabricmc.fabric-api:fabric-api:0.141.4+1.21.11")
}

loom.runs.named("clientGameTest") {
    property("xploits.bench.baseline", file("bench/baseline.json").absolutePath)
    property("xploits.bench.out", layout.buildDirectory.dir("bench").get().asFile.absolutePath)
    project.findProperty("bench.only")?.let { property("xploits.bench.only", it.toString()) }
    if (project.hasProperty("bench.updateBaseline")) property("xploits.bench.update-baseline", "true")
}

// The bench's verdict is read here, on the Gradle side, not from the client's exit code: a client that
// stops mid-bench (a closed window, a crash) can exit 0. Each run starts from an empty build/bench, and
// benchVerify, which always follows runClientGameTest, fails unless the report is there, every scenario
// in it is PASS, DONE or SKIPPED, and the bench's final hygiene scan wrote "clean" into it.
val benchOut = layout.buildDirectory.dir("bench")
val benchReport = benchOut.map { it.file("report-${project.version}.json") }

tasks.named("runClientGameTest") {
    doFirst { delete(benchOut) }
    finalizedBy("benchVerify")
}

tasks.register("benchVerify") {
    group = "verification"
    description = "Fails unless the in-game bench report says every scenario passed (run by runClientGameTest)."
    doLast {
        val file = benchReport.get().asFile
        if (!file.isFile) {
            throw GradleException("bench: no report (build/bench/${file.name}): the bench ended before writing it; see the client log")
        }
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val scenarios = (root["scenarios"] as? List<Map<String, Any?>>).orEmpty()
        val counts = linkedMapOf<String, Int>()
        var regressions = 0
        val blocking = mutableListOf<String>()
        for (scenario in scenarios) {
            val status = scenario["status"]?.toString() ?: "MISSING"
            counts.merge(status, 1, Int::plus)
            regressions += (scenario["regressions"] as? List<*>)?.size ?: 0
            if (status !in setOf("PASS", "DONE", "SKIPPED")) {
                val error = scenario["error"]?.let { " ($it)" } ?: ""
                blocking += "${scenario["name"]} $status$error"
            }
        }
        val hygiene = root["hygiene"]
        val hygieneText = when (hygiene) {
            "clean" -> "hygiene clean"
            null -> "hygiene not scanned"
            else -> "hygiene ERROR"
        }
        // The scenario count, and which names -Pbench.only picked (or "full run"), so a partial run is
        // visible here and not only in the report's own "only" field.
        val only = root["only"]?.toString()
        val scope = if (only != null) "only $only" else "full run"
        val summary = (listOf("${scenarios.size} scenario(s), $scope") +
            counts.map { (status, n) -> "$n $status" } + "$regressions regression(s)" + hygieneText).joinToString(", ")
        logger.lifecycle("bench: $summary")
        if (scenarios.isEmpty()) throw GradleException("bench: the report lists no scenario")
        if (blocking.isNotEmpty() || hygiene != "clean") {
            val reasons = blocking + (if (hygiene != "clean") listOf(hygieneText) else emptyList())
            throw GradleException("bench failed: " + reasons.joinToString("; "))
        }
    }
}

// ./gradlew build compiles the bench, so a break shows up there; it never runs it (no window).
tasks.named("check") { dependsOn("gametestClasses") }

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Xdoclint:reference/private")
    }

    test {
        useJUnitPlatform()
        // BoundaryTest reads the bench's sources and BenchBaselineTest the baseline: rerun when they change.
        inputs.files(fileTree("src/gametest/java"), fileTree("bench"))
    }
}
