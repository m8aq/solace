plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

apply<BootstrapPlugin>()

version = rootProject.version

val embed: Configuration by configurations.creating
val remap: Configuration by configurations.creating
val blob: Configuration by configurations.creating
val bootstrap: Configuration by configurations.creating

dependencies {
    embed(projects.solaceApi)
    embed(projects.bindings)
    embed(projects.hub)
    embed(projects.common)
    embed(projects.ui)
    embed(projects.bundled)

    bootstrap(projects.collisionMaps)
    blob(projects.collisionMaps)

    compileOnly(libs.runelite.client) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.runelite.api)
    compileOnly(libs.guice)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // bundled into the production fat jar only (shadowJar), not the library jar
    implementation(libs.runelite.client)
    implementation(libs.runelite.api)
    implementation(libs.runelite.jshell)

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
    implementation(libs.guice)

    implementation(projects.solaceApi)
    implementation(projects.collisionMaps)
    implementation(projects.bindings)
    implementation(projects.hub)
    implementation(projects.common)
    implementation(projects.ui)

    runtimeOnly(projects.bundled)
}

tasks {
    processResources {
        from("${rootProject.projectDir}/mappings/version-package.json") {
            into("net/solace/loader")
        }
    }

    jar {
        dependsOn(embed)
        from(embed.map { if (it.isDirectory) it else zipTree(it) })
    }

    withType<BootstrapTask> {
        mainJarFile.set(file("${project.projectDir}/build/libs/${project.name}-${project.version}.jar"))
        dependsOn(jar)
    }

    shadowJar {
        group = "solace"
        description = "Build the production Solace fat jar"
        archiveBaseName.set("solace")
        archiveClassifier.set("")
        dependsOn(jar)
        manifest {
            attributes["Main-Class"] = "net.solace.loader.SolaceLauncher"
        }
        mergeServiceFiles()
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
        )
    }

    register<JavaExec>("runDev") {
        group = "solace"
        description = "Run Solace with RuneLite debug and developer-mode flags"
        dependsOn(classes)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("net.solace.loader.SolaceLoaderDev")
        jvmArgs("-Drunelite.launcher.version=dev")

        // -ea matters: PluginManagerImpl.startPlugin/stopPlugin guard the EDT requirement with
        // assertions, which are off by default. Without this a control-API command that forgot to
        // hop to the EDT would silently corrupt activePlugins instead of failing loudly.
        jvmArgs("-ea")

        systemProperty("solace.controlapi", "true")
        systemProperty(
            "solace.controlapi.port",
            providers.gradleProperty("controlApiPort").getOrElse("7780"),
        )

        // :devplugins is deliberately in NEITHER embed NOR runtimeOnly - its classes must never be on
        // the app classpath, or loadCorePlugins() would find them by classpath scan and the hot-swap
        // loader would be shadowed by an unreloadable copy.
        dependsOn(":devplugins:jar")
        systemProperty(
            "solace.devplugins.jar",
            project(":devplugins").layout.buildDirectory.file("libs/devplugins.jar").get().asFile.path,
        )

        // Forwarded from -P on the Gradle command line. runDev forks its own JVM, so
        // -Dorg.gradle.jvmargs would configure the daemon and never reach the client.
        // solaceArgs carries credentials, so it is passed here rather than through the environment,
        // which every child process would inherit.
        listOf(
            "solaceArgs" to "solace.args",
            "controlApiToken" to "solace.controlapi.token",
        ).forEach { (gradleProperty, systemProperty) ->
            providers.gradleProperty(gradleProperty).orNull?.let { systemProperty(systemProperty, it) }
        }
    }

    named("build") {
        dependsOn(shadowJar)
    }

    /**
     * :loader's own classes only, for the hot-reloadable layer.
     *
     * Deliberately NOT the `embed` fat-packing that `jar` does - the layer needs the modules as
     * separate jars so each can be rebuilt and watched independently, and a fat jar would also put a
     * second copy of api/sdk/bindings inside this one.
     */
    register<Jar>("layerJar") {
        group = "solace"
        description = "Loader classes only, for the reloadable Solace layer"
        archiveClassifier.set("layer")
        from(sourceSets["main"].output)
    }
}
