version = rootProject.version

// DELIBERATELY NO `projects.*` DEPENDENCIES.
//
// This module is the non-reloadable bootstrap. It must never put a net.solace.* class on the
// application classloader: PluginManagerImpl.loadCorePlugins() scans with Guava's ClassPath, whose
// scanner recurses to the PARENT loader first, so a stray solace jar on java.class.path would make
// every bundled plugin load from unreloadable classes - silently, with no error.
//
// Gradle enforces that invariant here; SolaceLayerLauncher asserts it again at runtime.
//
// What IS declared is exactly the third-party set that must be shared with the layer, because Guice
// needs the same Class objects on both sides for RuneLite.getInjector().createChildInjector() to work.
dependencies {
    // No okhttp exclude here. :loader excludes it from its compileOnly declaration only; its
    // implementation dependency pulls okhttp in, and RuneLite needs it at runtime.
    implementation(libs.runelite.client)
    implementation(libs.runelite.api)
    implementation(libs.runelite.jshell)

    implementation(libs.guice)
    implementation(libs.gson)
    implementation(libs.pf4j) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(libs.pf4j.update) {
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(libs.asm.util)
    implementation(libs.miglayout)
    implementation(libs.kotlin.stdlib)
    implementation(libs.reactivex.rxjava3)
    implementation(libs.eclipse.collections)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

/**
 * The layer classpath, built from explicit Jar-task outputs.
 *
 * Not from a resolvable configuration: `layer(projects.loader)` would drag :loader's `implementation`
 * dependencies (RuneLite, Guice, pf4j, gson...) into the child loader, which is exactly what must not
 * happen - those types have to be shared with the parent.
 */
// Gradle configures projects alphabetically, so :devboot is configured before :loader - and looking
// up :loader's layerJar task below would fail with "task not found". Force those projects to be
// evaluated first.
val layerProjects = listOf(
    ":solace-api", ":bindings", ":loader",
    ":ui", ":hub", ":common", ":bundled", ":collision-maps",
)
layerProjects.forEach { evaluationDependsOn(it) }

val layerJarTasks = listOf(
    ":solace-api" to "jar",
    ":bindings" to "jar",
    ":loader" to "layerJar",
    ":ui" to "jar",
    ":hub" to "jar",
    ":common" to "jar",
    ":bundled" to "jar",
    ":collision-maps" to "jar",
).map { (path, task) -> project(path).tasks.named<Jar>(task) }

tasks {
    register<JavaExec>("runDev") {
        group = "solace"
        description = "Run Solace in a reloadable classloader over RuneLite"

        dependsOn(rootProject.tasks.named("layerJars"))
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("net.solace.boot.SolaceLayerLauncher")

        jvmArgs("-Drunelite.launcher.version=dev")

        // Keeps the EDT assertions in PluginManagerImpl live - without -ea a violation from an HTTP
        // pool thread silently corrupts the active plugin list instead of failing.
        jvmArgs("-ea")

        // A classloader leak shows up here first. Fail fast rather than degrading the machine.
        jvmArgs("-XX:MaxMetaspaceSize=768m")

        // LeakReport.checkFocus nulls KeyboardFocusManager.newFocusOwner, which has no public
        // accessor and would otherwise pin a generation through the focused component's captured
        // AccessControlContext. Dev-only; the production launcher opens nothing.
        jvmArgs("--add-opens", "java.desktop/java.awt=ALL-UNNAMED")

        systemProperty(
            "solace.layer.path",
            layerJarTasks.joinToString(File.pathSeparator) { it.get().archiveFile.get().asFile.path },
        )

        systemProperty("solace.controlapi", "true")
        systemProperty(
            "solace.controlapi.port",
            providers.gradleProperty("controlApiPort").getOrElse("7780"),
        )
        systemProperty(
            "solace.reload.port",
            providers.gradleProperty("reloadPort").getOrElse("7781"),
        )
        systemProperty(
            "solace.devplugins.jar",
            project(":devplugins").layout.buildDirectory.file("libs/devplugins.jar").get().asFile.path,
        )

        listOf(
            "solaceArgs" to "solace.args",
            "controlApiToken" to "solace.controlapi.token",
        ).forEach { (gradleProperty, systemProperty) ->
            providers.gradleProperty(gradleProperty).orNull?.let { systemProperty(systemProperty, it) }
        }
    }
}
