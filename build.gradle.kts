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
