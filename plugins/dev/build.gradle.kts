version = rootProject.version

// Mirrors :bundled deliberately - everything compileOnly. Nothing from this module may end up inside
// the jar except the dev plugins themselves; see DevPluginClassLoader for why that is load-bearing
// rather than tidy, and the verifyJarContents check below for the enforcement.
dependencies {
    compileOnly(libs.runelite.client) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.runelite.api)
    compileOnly(libs.guice)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    compileOnly(projects.solaceApi)
    compileOnly(libs.pf4j)
    compileOnly(libs.reactivex.rxjava3)

    compileOnly(projects.ui)
    compileOnly(projects.hub)
}

val pluginPackagePath = "net/solace/loader/plugins/dev/"

tasks.jar {
    archiveFileName.set("devplugins.jar")

    manifest {
        // Read by DevPluginHotSwapService.preflight. Every class listed here must extend
        // net.solace.api.plugins.Plugin and carry @PluginDescriptor.
        attributes["Solace-Plugin-Classes"] = providers
            .gradleProperty("devPluginClasses")
            .getOrElse(scanForPluginClasses())
    }

    doLast {
        verifyJarContents(archiveFile.get().asFile)
    }
}

/**
 * Every top-level class under the dev plugin package, so a plugin dropped into plugins/dev is picked
 * up without editing the build. Override with -PdevPluginClasses=a,b to reload a subset.
 */
fun scanForPluginClasses(): String {
    val root = file("src/main/java/$pluginPackagePath")
    if (!root.isDirectory) return ""
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .map { "net.solace.loader.plugins.dev." + it.relativeTo(root).path.removeSuffix(".java").replace(File.separatorChar, '.') }
        .sorted()
        .joinToString(",")
}

/**
 * Fails the build if the jar carries anything outside the dev plugin package.
 *
 * A stray copy of net.solace.api.* would be loaded by the reload classloader as a
 * *second* Class object for a name the app classloader already owns. The SDK facades cache their
 * interface in a `private static final` at class-load, so the duplicate re-resolves it and appears to
 * work, right up until a ClassCastException at the first boundary where the two meet. All-compileOnly
 * makes this true by construction; this check makes it non-negotiable.
 */
fun verifyJarContents(jar: File) {
    val strays = zipTree(jar).files
        .map { it.absolutePath }
        .filter { it.endsWith(".class") }
        .filterNot { it.contains(pluginPackagePath) }
    if (strays.isNotEmpty()) {
        throw GradleException(
            "devplugins.jar must contain only $pluginPackagePath classes, found: " +
                strays.take(5).joinToString(", "),
        )
    }
}
