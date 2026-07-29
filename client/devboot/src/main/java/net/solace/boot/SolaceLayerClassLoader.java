package net.solace.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Loads one generation of the entire Solace layer.
 *
 * <p>This is the <b>inverse</b> of {@code DevPluginClassLoader}. That one keeps {@code net.solace.*}
 * parent-first so a dev plugin can never get a second copy of the API; this one makes
 * {@code net.solace.*} <b>child-first</b>, because here the API <em>is</em> what we are reloading.
 *
 * <p>Everything else stays parent-first, and that is not a preference - it is forced. {@code
 * SolaceLoader.start} calls {@code RuneLite.getInjector().createChildInjector(modules)}, which
 * requires {@code com.google.inject.*} and every RuneLite type bound in the parent injector to be the
 * <em>same</em> {@code Class} objects on both sides. A second Guice in the child would either reject
 * the modules or, far worse, build an injector whose {@code Key<EventBus>} never matches the parent's
 * - Solace would come up "successfully" with a completely disconnected event bus.
 *
 * <p>Classes come from a private per-generation copy of each jar, for the same reason
 * {@code DevPluginClassLoader} does it: a {@code URLClassLoader} resolves lazily and keeps reading its
 * jars for the whole life of the generation, while {@code gradle -t} rewrites those files in place.
 * Every class the running generation had not yet touched would then fail with
 * {@code NoClassDefFoundError} - a generation that reports itself healthy while half its code has
 * vanished.
 */
public final class SolaceLayerClassLoader extends URLClassLoader {
    /**
     * Loaded from the parent, never from the layer jars. Anything not matched here and not excluded
     * below is child-first - in practice exactly {@code net.solace.*}.
     */
    private static final String[] PARENT_FIRST = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "net.runelite.",
            "com.google.",          // guice, guava and gson all have to be shared
            "org.slf4j.", "ch.qos.logback.",
            "org.pf4j.", "io.reactivex.", "org.eclipse.collections.", "net.miginfocom.",
            "org.objectweb.asm.", "joptsimple.", "kotlin.",
            "org.apache.commons.", "org.json.", "org.fest.", "lombok.",
            "net.solace.boot.",     // the bootstrap itself must never be duplicated
    };

    /**
     * Layer jars that legitimately ship no classes, so the code check below must not reject them.
     * {@code :collision-maps} is a single packed region blob.
     */
    private static final String[] RESOURCE_ONLY_JARS = {"collision-maps"};

    private final ClassLoader app;
    private final List<Path> copies;

    /** Opens a generation, snapshotting every jar so later rebuilds cannot reach it. */
    public static SolaceLayerClassLoader open(List<Path> jars, int generation, ClassLoader app)
            throws IOException {
        var copies = new ArrayList<Path>(jars.size());
        try {
            for (var jar : jars) {
                assertCarriesLayerClasses(jar);
                copies.add(privateCopyOf(jar, generation));
            }
        } catch (IOException e) {
            deleteAll(copies);
            throw e;
        }

        var urls = new URL[copies.size()];
        for (var i = 0; i < copies.size(); i++) {
            urls[i] = copies.get(i).toUri().toURL();
        }
        return new SolaceLayerClassLoader(urls, copies, app);
    }

    private SolaceLayerClassLoader(URL[] urls, List<Path> copies, ClassLoader app) {
        super(urls, null);
        this.copies = copies;
        this.app = app;
    }

    /**
     * Rejects a layer jar that carries no classes at all.
     *
     * <p>Gradle's incremental state can drift out of sync with what is actually on disk - a task
     * reports up-to-date while its output jar holds nothing but a manifest and resources. Without this
     * check the layer comes up "successfully" over a jar with no code in it, and the symptom is
     * arbitrarily far from the cause: a Solace panel listing one plugin instead of twenty-five, or a
     * ClassNotFoundException for a class whose source is plainly present. Both were hit repeatedly.
     *
     * <p>Fails the whole generation rather than skipping the jar: a partial layer is worse than none,
     * and on a reload this surfaces as a rejected candidate with the running generation untouched.
     */
    private static void assertCarriesLayerClasses(Path jar) throws IOException {
        var name = jar.getFileName().toString();
        for (var prefix : RESOURCE_ONLY_JARS) {
            if (name.startsWith(prefix)) {
                return;
            }
        }

        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(".class")) {
                    return;
                }
            }
        }
        throw new IOException(name + " contains no classes - a stale or truncated build."
                + " Rebuild with: ./gradlew --rerun-tasks layerJars");
    }

    private static Path privateCopyOf(Path jar, int generation) throws IOException {
        var name = jar.getFileName().toString().replace(".jar", "");
        var copy = Files.createTempFile("solace-layer-" + generation + "-" + name + "-", ".jar");
        try {
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        copy.toFile().deleteOnExit();
        return copy;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                return resolved(loaded, resolve);
            }

            if (isParentFirst(name)) {
                return resolved(app.loadClass(name), resolve);
            }

            try {
                return resolved(findClass(name), resolve);
            } catch (ClassNotFoundException e) {
                return resolved(app.loadClass(name), resolve);
            }
        }
    }

    private Class<?> resolved(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }

    static boolean isParentFirst(String name) {
        for (var prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            deleteAll(copies);
        }
    }

    private static void deleteAll(List<Path> paths) {
        for (var path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // The JVM's deleteOnExit will get it.
            }
        }
    }
}
