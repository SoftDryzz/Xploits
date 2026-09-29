import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

// The addon as one mod in development runs, its classes and its resources together (fabric.classPathGroups), as in
// the jar. Without it Fabric knows only the resources folder, where fabric.mod.json is, and the console window,
// launched with the mod's own paths as its classpath, cannot find its classes. Development runs only: the jar is
// built as before.
loom {
    mods {
        register("xploits") {
            sourceSet("main")
        }
    }
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
    // the Minecraft version, the effective simulated ping (R3-12), every file under src/gametest, the build files
    // (this one, settings.gradle.kts, gradle.properties, gradle/libs.versions.toml), the mixin configs and their
    // packages' Java files, the recorder, and the scenario (MeteorCache.inputs). -Pbench.fresh measures them
    // again, and a release needs it (benchVerify); -Pbench.verifySettle measures them again too.
    if (project.hasProperty("bench.fresh")) property("xploits.bench.fresh", "true")
    property("xploits.bench.project", projectDir.absolutePath)
    // R3-12: every crystal-aura MEASURE plays over a simulated round trip of PingDelay.BENCH_PING_MS (100 ms);
    // -Pbench.ping overrides it (0 restores today's lock-step for those runs too).
    project.findProperty("bench.ping")?.let { property("xploits.bench.ping", it.toString()) }
    // Task A5: -Pbench.shard=k/n (1 <= k <= n <= ShardPlan.MAX_SHARDS) plays only shard k of the selection
    // (bench/parallel.ps1, or a hand-run shard); the report records the commit and whether this worktree's
    // tree was clean, so ReportMerge can refuse a merge of shards that did not all run the same code.
    project.findProperty("bench.shard")?.let {
        property("xploits.bench.shard", it.toString())
        property("xploits.bench.commit", gitCommit())
        property("xploits.bench.tree-clean", gitTreeClean().toString())
        // A cap only for a sharded client, never the plain single-client run: measured, not guessed — a
        // short two-scenario probe run (ca-still, capp-balanced-still) peaked at ~2.1 GB working set on this
        // machine; 3 GB leaves real headroom for a longer shard's many scenarios while keeping 4 clients at
        // once (12 GB) far under the 64 GB available. Window, render and fps options are left at the
        // gametest run's own defaults, since BenchTest.keepFullFrameRate is the only one of those that could
        // change a measurement, and it already runs unconditionally.
        vmArg("-Xmx3G")
    }
    // R3-12: Fabric's own client-gametest NetworkSynchronizer assumes every packet is handled on the netty
    // thread almost at once and blocks each frame until it is (waitForPacketHandlers, 10 s timeout); the
    // bench's simulated ping deliberately holds a packet longer than that inside the pipeline, which the
    // synchronizer treats as "interfacing with packets at a lower level" and, once it has waited out its own
    // timeout, crashes the client with "Network synchronizer in invalid state". Fabric's own log line for that
    // error names this exact property as the fix, so the bench always disables it, ping or not: a run with no
    // simulated ping installs no extra handler and was never at risk, and one JVM cannot toggle a static-final
    // system property mid-run scenario by scenario.
    property("fabric.client.gametest.disableNetworkSynchronizer", "true")
    // -Pshots (tools/shots.ps1): the README's screenshots (Shots) instead of the bench, into build/shots. It writes
    // no bench report, so benchVerify does not run after it, and build/bench is left as it is.
    if (project.hasProperty("shots")) property("xploits.shots", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
    // -Pbench.lab=<names> (or all): the lab's worst cases (LabWorstCase) instead of the bench, each writing a trace to
    // build/bench-lab. Like -Pshots, no bench report, so benchVerify does not run and build/bench is left as it is.
    project.findProperty("bench.lab")?.let {
        property("xploits.lab", it.toString())
        property("xploits.lab.out", layout.buildDirectory.dir("bench-lab").get().asFile.absolutePath)
    }
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
    val shots = project.hasProperty("shots") || project.hasProperty("bench.lab")
    doFirst { if (!shots) delete(fileTree(benchOut) { exclude("meteor-cache/**") }) }
    finalizedBy("benchVerify")
}

// The release rules are pure Java in the bench core (ReleaseGate), unit-tested there, and run here from its
// compiled class, alone in a loader of its own: build.gradle.kts keeps no copy of them to drift.
val benchCoreClasses = sourceSets.named("gametest").map { it.output.classesDirs }

tasks.register("benchVerify") {
    group = "verification"
    description = "Fails unless the in-game bench report says every scenario passed (run by runClientGameTest)."
    dependsOn("compileGametestJava")
    val shots = project.hasProperty("shots") || project.hasProperty("bench.lab")
    onlyIf("a -Pshots or -Pbench.lab run writes no bench report") { !shots }
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

/** The current commit's full SHA (task A5: every shard's report records it). */
fun gitCommit(): String =
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()

/** Whether this worktree has no uncommitted change (task A5: every shard's report records it; the
 * shard worktrees are always reset to HEAD before a run, so this is really only informative for shard 1's
 * own worktree, and for {@code bench/parallel.ps1}'s own dirty-tree refusal before it starts any shard). */
fun gitTreeClean(): Boolean =
    providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isBlank()

// Task A5: merges shard 1's report (this worktree's own build/bench) with shards 2..n's, each in its own
// detached worktree .worktrees/bench-shard-<k> (a sibling of this worktree: ../bench-shard-<k> from here),
// into this worktree's build/bench/report-<version>.json/.md, then runs benchVerify on the merged report
// unchanged — bench/parallel.ps1 is what actually runs the n clients and calls this task afterwards.
// The merge logic itself is ReportMerge, pure core with its own unit tests; this task only locates the n
// shard report files and runs BenchParallelRunner (plain Java, no Fabric/Meteor: a JavaExec, not a game launch)
// against them.
tasks.register<JavaExec>("benchMerge") {
    group = "verification"
    description = "Merges n -Pbench.shard=k/n shard reports (task A5, bench/parallel.ps1) into build/bench/report-<version>.json and runs benchVerify on it."
    dependsOn("compileGametestJava")
    classpath = sourceSets.named("gametest").get().runtimeClasspath
    mainClass.set("com.xploits.bench.BenchParallelRunner")
    val n = (project.findProperty("bench.shards") as String?)?.toInt() ?: 1
    val version = project.version.toString()
    val outFolder = benchOut.get().asFile
    val baselineFile = file("bench/baseline.json").absolutePath
    val finalJson = outFolder.resolve("report-$version.json")
    val finalMd = outFolder.resolve("report-$version.md")
    // Shard 1 always runs in this worktree, so its own live report is written to the exact path the merged
    // report also belongs at (report-<version>.json/.md) — the same stale-report guard runClientGameTest's
    // own doFirst has cannot simply delete that path first, or there would be nothing left for shard 1's own
    // input (this failed a real smoke run before this fix: "shard report missing"). Instead, shard 1's fresh
    // report is staged aside first (renamed, not copied: no leftover duplicate to go stale itself), which
    // both frees the final path — so a refused merge (BenchParallelRunner exits 1 before writing anything)
    // leaves NOTHING at report-<version>.json/.md for the finalizing benchVerify to misread as current,
    // exactly the "a partial merge never passes the gate" case the brief rules out — and gives BenchParallelRunner
    // a stable, private path to read shard 1's data from. Never touches meteor-cache/.
    val shard1Staging = outFolder.resolve("report-$version-shard1.json")
    val shard1StagingMd = outFolder.resolve("report-$version-shard1.md")
    doFirst {
        delete(shard1Staging, shard1StagingMd)
        if (finalJson.isFile) Files.move(finalJson.toPath(), shard1Staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (finalMd.isFile) Files.move(finalMd.toPath(), shard1StagingMd.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    // -Pbench.updateBaseline here, never on the individual shard runs (bench/parallel.ps1 never passes it
    // down to them): a shard only sees its own slice of the scenarios, and shards 2..n's own baseline.json
    // lives in a disposable worktree, so only updating it once, here, from the complete merged report, is
    // correct (task A5 requirement 3).
    val updateBaseline = project.hasProperty("bench.updateBaseline")
    val shardFiles = mutableListOf(shard1Staging.absolutePath)
    for (k in 2..n) {
        shardFiles += file("../bench-shard-$k/build/bench/report-$version.json").absolutePath
    }
    args = listOf(version, outFolder.absolutePath, baselineFile, updateBaseline.toString()) + shardFiles
    finalizedBy("benchVerify")
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
