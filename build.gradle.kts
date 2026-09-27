import java.net.URLClassLoader

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

// The bench's pure core (src/gametest/java/com/xploits/bench/core: the acceptance rules) is compiled with
// the unit tests too, so ./gradlew build tests it. Only that folder, with its own filter, and never into
// the shipped jar, which is built from the main source set alone.
val benchCore = objects.sourceDirectorySet("benchCore", "the bench's pure core").apply {
    srcDir("src/gametest/java")
    include("com/xploits/bench/core/**")
}
sourceSets.named("test") { java.source(benchCore) }

loom.runs.named("clientGameTest") {
    property("xploits.bench.baseline", file("bench/baseline.json").absolutePath)
    property("xploits.bench.out", layout.buildDirectory.dir("bench").get().asFile.absolutePath)
    project.findProperty("bench.only")?.let { property("xploits.bench.only", it.toString()) }
    if (project.hasProperty("bench.updateBaseline")) property("xploits.bench.update-baseline", "true")
    // R3-6: a settled crystal-aura run runs on to 30 s and must end with the metrics it had when it settled.
    if (project.hasProperty("bench.verifySettle")) property("xploits.bench.verify-settle", "true")
    // R3-9: the everyday run (the default) plays crystal-aura++ at Balanced only; -Pbench.full plays everything
    // and is the one a release needs. -Pbench.only wins over both.
    if (project.hasProperty("bench.full")) property("xploits.bench.full", "true")
    // R3-9: Meteor's ca-* results are served from build/bench/meteor-cache while its key matches: Meteor's jar,
    // the Minecraft version, every file under src/gametest, the build files (this one, settings.gradle.kts,
    // gradle.properties, gradle/libs.versions.toml), the mixin configs and their packages' Java files, the
    // recorder, and the scenario (MeteorCache.inputs). -Pbench.fresh measures them again, and a release needs it
    // (benchVerify); -Pbench.verifySettle measures them again too.
    if (project.hasProperty("bench.fresh")) property("xploits.bench.fresh", "true")
    property("xploits.bench.project", projectDir.absolutePath)
}

// The bench's verdict is read here, on the Gradle side, not from the client's exit code: a client that
// stops mid-bench (a closed window, a crash) can exit 0. Each run starts from an empty build/bench but for
// the Meteor cache (build/bench/meteor-cache, which its own key keeps valid), and benchVerify, which always
// follows runClientGameTest, fails unless the report is there, every scenario in it is PASS, DONE or SKIPPED
// (SKIPPED: not in the everyday run), the bench's final hygiene scan wrote "clean" into it, and a full run did not
// serve any ca-* from the Meteor cache (ReleaseGate).
val benchOut = layout.buildDirectory.dir("bench")
val benchReport = benchOut.map { it.file("report-${project.version}.json") }

tasks.named("runClientGameTest") {
    doFirst { delete(fileTree(benchOut) { exclude("meteor-cache/**") }) }
    finalizedBy("benchVerify")
}

// The release rules are pure Java in the bench core (ReleaseGate), unit-tested there, and run here from its
// compiled class, alone in a loader of its own: build.gradle.kts keeps no copy of them to drift.
val benchCoreClasses = sourceSets.named("gametest").map { it.output.classesDirs }

tasks.register("benchVerify") {
    group = "verification"
    description = "Fails unless the in-game bench report says every scenario passed (run by runClientGameTest)."
    dependsOn("compileGametestJava")
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
        // The scenario count, and which names -Pbench.only picked, or which run it was ("everyday run" or
        // "full run"), so a partial or everyday run is visible here and not only in the report's own fields.
        val only = root["only"]?.toString()
        val profile = root["profile"]?.toString()
        val scope = if (only != null) "only $only" else if (profile == "everyday") "everyday run" else "full run"
        val cached = scenarios.count { it["cached"] == true }
        val summary = (listOf("${scenarios.size} scenario(s), $scope") +
            counts.map { (status, n) -> "$n $status" + (if (status == "DONE" && cached > 0) " ($cached cached)" else "") } +
            "$regressions regression(s)" + hygieneText).joinToString(", ")
        logger.lifecycle("bench: $summary")
        // R3-9: only -Pbench.full measures every level; the everyday run is never the release gate.
        if (only == null && profile == "everyday") {
            logger.lifecycle("bench: everyday run — not valid for a release (use -Pbench.full)")
        }
        // R3-9, fix round 1: a release re-measures Meteor, so a full run that served a ca-* from the Meteor cache
        // fails ("bench: full run with cached Meteor results — not valid for a release (add -Pbench.fresh)").
        val urls = benchCoreClasses.get().files.map { it.toURI().toURL() }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val release = URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.loadClass("com.xploits.bench.core.ReleaseGate").getMethod("check", Map::class.java)
                .invoke(null, root) as List<String>
        }
        release.forEach { logger.lifecycle(it) }
        // The crystal-aura++ verdicts ("capp: n ACCEPT / m REJECT / k INCOMPLETE / j NOT_APPLICABLE") and the
        // strict recommendation at each risk level over its pairs of the full bench ("capp Safe: YES/NO (n of m
        // applicable)", then Balanced and Aggressive; in the everyday run only Balanced, then one "not measured"
        // line for the others), when any scenario was judged: shown, never a reason to
        // fail (a death in a crystal-aura++ run fails as an ERROR above).
        root["compare"]?.let { logger.lifecycle("bench: $it") }
        (root["recommendation"] as? List<*>)?.forEach { logger.lifecycle("bench: $it") }
        if (scenarios.isEmpty()) throw GradleException("bench: the report lists no scenario")
        if (blocking.isNotEmpty() || hygiene != "clean" || release.isNotEmpty()) {
            val reasons = blocking + (if (hygiene != "clean") listOf(hygieneText) else emptyList()) +
                release.map { it.removePrefix("bench: ") }
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
