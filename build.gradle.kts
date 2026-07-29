plugins {
    java
}

group = "net.solace"
version = "0.0.5"

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.runelite.net")
        mavenLocal()
    }

    tasks {
        jar {
            isReproducibleFileOrder = true
            isPreserveFileTimestamps = false
        }

        withType<JavaCompile> {
            options.encoding = "UTF-8"
        }

        java {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

tasks {
    jar {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    register<JavaExec>("generateVersionPackage") {
        group = "solace"
        description = "Convert Solace canonical JSON into mappings/version-package.json"
        dependsOn(":mappings-tool:classes")
        classpath = project(":mappings-tool").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("net.solace.mappings.tool.GenerateVersionPackage")
    }

    register<JavaExec>("migrateMappings") {
        group = "solace"
        description = "Run Solace MappingGenerator (old jar, old mappings, new jar, out json, [log])"
        dependsOn(":mappings-generator:classes")
        classpath = project(":mappings-generator").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("net.solace.mappings.generator.MappingGenerator")
    }

    register("buildClient") {
        group = "solace"
        description = "Build the production Solace fat jar"
        dependsOn(":loader:shadowJar")
    }

    /**
     * Every jar in the hot-reloadable Solace layer. This is the task to run under `gradle -t`:
     *
     *     ./gradlew -t layerJars
     */
    register("layerJars") {
        group = "solace"
        description = "Build every jar in the reloadable Solace layer"
        dependsOn(
            ":solace-api:jar",
            ":bindings:jar",
            ":loader:layerJar",
            ":ui:jar",
            ":hub:jar",
            ":common:jar",
            ":bundled:jar",
            ":collision-maps:jar",
            ":devplugins:jar",
        )
    }
}
