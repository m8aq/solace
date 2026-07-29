package net.solace.loader.hotswap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads one generation of the dev plugin jar.
 *
 * <p><b>Classes come from a private copy of the jar, not the build output.</b> A
 * {@link URLClassLoader} resolves lazily, so a live generation keeps reading its jar for as long as
 * it runs - and a Gradle rebuild rewrites that file in place. Every class the running plugin had not
 * yet touched then fails with {@code NoClassDefFoundError}, surfacing as a plugin that reports itself
 * healthy while half its code has vanished. Copying makes a generation immune to whatever happens to
 * the build output afterwards.
 *
 * <p><b>Parent-first for everything except the dev plugins themselves.</b> This is the one place this
 * port deliberately diverges from easy-rl, whose loader is jar-first for all names. Solace's SDK
 * facades cache their interface at class-load:
 *
 * <pre>{@code private static final IClient CLIENT = Static.getClient(); }</pre>
 *
 * If this loader ever produced a second {@code net.solace.api.game.Client}, that copy would re-run the
 * initializer, get a non-null value, and <em>appear</em> to work - until the first boundary where the
 * two distinct {@code Class} objects meet and it fails with a {@code ClassCastException} nowhere near
 * the cause. Worse, a second {@code net.solace.api.Static} would have a permanently null
 * {@code injector}, because that field is assigned exactly once at startup on the app-loader copy.
 *
 * <p>The allowlist prevents both by construction. {@code :devplugins} being all-{@code compileOnly}
 * means the jar should never contain those classes anyway - this is the second of two locks, the
 * first being the jar-contents check in {@code plugins/dev/build.gradle.kts}.
 */
final class DevPluginClassLoader extends URLClassLoader {
    /** Loaded from the parent, never from the jar. */
    private static final String[] PARENT_FIRST = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.solace.api.", "net.solace.api.", "net.solace.impl.", "net.solace.loader.",
            "net.solace.sdn.", "net.runelite.",
            "com.google.", "org.slf4j.", "ch.qos.logback.", "io.reactivex.", "org.pf4j.",
            "lombok.",
    };

    /** The one exception to {@code net.solace.loader.} - the dev plugins are what we are reloading. */
    private static final String PLUGIN_PACKAGE = "net.solace.loader.plugins.dev.";

    private final ClassLoader fallback;
    private final Path copy;

    /** Opens a generation, snapshotting {@code jar} so later rebuilds cannot reach it. */
    static DevPluginClassLoader open(Path jar, ClassLoader fallback) throws IOException {
        return new DevPluginClassLoader(privateCopyOf(jar), fallback);
    }

    private DevPluginClassLoader(Path copy, ClassLoader fallback) throws IOException {
        super(new URL[]{copy.toUri().toURL()}, null);
        this.fallback = fallback;
        this.copy = copy;
    }

    /**
     * A snapshot in the temp directory, named per generation rather than reused - two generations can
     * be alive at once while the outgoing one drains, and they must never share a file.
     */
    private static Path privateCopyOf(Path jar) throws IOException {
        var copy = Files.createTempFile("solace-devplugins-", ".jar");
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
                return resolved(fallback.loadClass(name), resolve);
            }

            try {
                return resolved(findClass(name), resolve);
            } catch (ClassNotFoundException e) {
                return resolved(fallback.loadClass(name), resolve);
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
        if (name.startsWith(PLUGIN_PACKAGE)) {
            return false;
        }
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
            if (copy != null) {
                Files.deleteIfExists(copy);
            }
        }
    }
}
