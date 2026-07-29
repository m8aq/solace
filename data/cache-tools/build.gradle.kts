apply<MapGeneratorPlugin>()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.runelite.cache)
    implementation(libs.runelite.arn)
    implementation(libs.lombok)
    implementation(projects.solaceApi)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)
    implementation(libs.guava)

    // we need gson 2.12.1 because the RL version does not support deserializing into java records
    implementation("com.google.code.gson:gson:2.12.1")

    annotationProcessor(libs.lombok)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named<JavaExec>("generateMap") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

// Dumps every object definition to ~/.solace/cache-dump/objects as one JSON per
// object. tools/build_osrs_scenery.py joins that against the wiki, which knows
// what an object is for but not its ids, sizes or collision flags.
tasks.register<JavaExec>("dumpObjects") {
    group = "cache"
    description = "Dump object definitions from the OSRS cache"
    mainClass.set("net.solace.rscache.dump.ObjectDumper")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}
