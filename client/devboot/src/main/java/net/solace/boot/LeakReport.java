package net.solace.boot;

import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.task.Scheduler;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Finds what a torn-down generation left behind.
 *
 * <p>Runs between {@code DevEntry.stop()} and closing the outgoing loader, from the bootstrap side, so
 * it can see both the dead classloader and the RuneLite singletons that outlive it. Anything still
 * referencing a class from that loader keeps the whole generation - its classes, its Guice injectors,
 * its config proxies - alive in Metaspace forever.
 *
 * <p>Where a public API allows it, findings are also cleaned up. The report is the point though: a
 * silent leak shows up only as Metaspace creeping up over an afternoon, whereas a named
 * {@code Class#method} tells you exactly which unregister is missing.
 *
 * <p>The EventBus sweep is the load-bearing one. {@code EventBus.register} builds a
 * {@code LambdaMetafactory} {@code Consumer} per {@code @Subscribe} method, defined in the
 * subscriber's nest - so a single missed unregister pins the generation through
 * {@code EventBus.subscribers}.
 */
final class LeakReport {
    private final ClassLoader outgoing;
    private final List<String> findings = new ArrayList<>();

    private LeakReport(ClassLoader outgoing) {
        this.outgoing = outgoing;
    }

    /** Runs every check, cleaning up what it can. Never throws. */
    static List<String> run(ClassLoader outgoing) {
        // Let the EDT drain first. ClientToolbar.removeNavigation defers to SwingUtilities.invokeLater,
        // so checking immediately after teardown reports every nav button as leaked when it is merely
        // queued for removal - false positives that train you to ignore the report.
        drainEventQueue();

        var report = new LeakReport(outgoing);
        report.safely("eventBus", report::checkEventBus);
        report.safely("scheduler", report::checkScheduler);
        report.safely("overlays", report::checkOverlays);
        report.safely("navButtons", report::checkNavButtons);
        report.safely("inputListeners", report::checkInputListeners);
        report.safely("clientThread", report::checkClientThread);
        report.safely("threads", report::checkThreads);
        report.safely("logback", report::checkLogAppenders);
        report.safely("focus", report::checkFocus);
        report.safely("guiceJit", report::checkGuiceJitBindings);
        report.safely("threadContext", report::checkThreadContextLoaders);
        report.safely("caches", LeakReport::flushClassKeyedCaches);
        return report.findings;
    }

    /**
     * Just-in-time bindings cached in RuneLite's <em>root</em> injector.
     *
     * <p>Solace builds a child injector per generation, but asking the parent for a type it does not
     * have a binding for makes Guice synthesise one and cache it in the parent's {@code jitBindings} -
     * where it outlives every child. A layer class bound in {@code SingletonScope} there holds a live
     * instance of itself, and the instance's captured {@code AccessControlContext} holds the loader.
     *
     * <p>Found in a heap dump as {@code InjectorImpl.jitBindings -> SingletonScope -> InventoryInspector
     * -> acc -> ProtectionDomain -> loader}.
     */
    private void checkGuiceJitBindings() {
        var injector = RuneLite.getInjector();
        var bindings = field(injector, "jitBindings");
        if (!(bindings instanceof Map)) {
            return;
        }
        var jit = (Map<?, ?>) bindings;
        for (var key : new ArrayList<>(jit.keySet())) {
            var type = rawTypeOf(key);
            if (type != null && type.getClassLoader() == outgoing) {
                findings.add("guice jit binding " + type.getName());
                jit.remove(key);
            }
        }
    }

    /** The raw {@code Class} a Guice {@code Key} is for, or null if it cannot be read. */
    private static Class<?> rawTypeOf(Object key) {
        try {
            var literal = key.getClass().getMethod("getTypeLiteral").invoke(key);
            return (Class<?>) literal.getClass().getMethod("getRawType").invoke(literal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A thread whose context classloader is the outgoing generation pins it from a GC root, even when
     * the thread itself belongs to RuneLite and is perfectly healthy - the TCCL is simply inherited
     * from whichever thread created it, so anything spawned while layer code was on the stack carries
     * the layer loader for its whole life.
     */
    private void checkThreadContextLoaders() {
        var replacement = LeakReport.class.getClassLoader();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getContextClassLoader() == outgoing) {
                findings.add("thread context loader on '" + thread.getName() + "'");
                try {
                    thread.setContextClassLoader(replacement);
                } catch (RuntimeException e) {
                    findings.add("  (failed to reset: " + e + ")");
                }
            }
        }
    }

    /**
     * Swing's focus machinery keeps static references to the component that last held focus. Any
     * layer-owned field - the plugin search bar, a config text field - therefore pins the generation,
     * and not through the component's own class: {@code JComponent} captures an
     * {@code AccessControlContext} at construction, whose {@code ProtectionDomain} names the loader
     * that defined the calling class. So the chain is
     * {@code KeyboardFocusManager.newFocusOwner -> JTextField.acc -> ProtectionDomain -> loader}.
     *
     * <p>Like the logback appenders, nothing in the Solace object graph points at this, so every other
     * check here is blind to it - it was found by walking a heap dump back to a GC root.
     *
     * <p>{@code newFocusOwner} has no public accessor, hence the reflection and the
     * {@code --add-opens java.desktop/java.awt} on {@code :devboot:runDev}.
     */
    private void checkFocus() {
        var manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (fromOutgoing(manager.getFocusOwner()) || fromOutgoing(manager.getPermanentFocusOwner())) {
            findings.add("focus owner " + manager.getFocusOwner().getClass().getName());
            manager.clearGlobalFocusOwner();
        }
        clearStaticIfOutgoing(java.awt.KeyboardFocusManager.class, "newFocusOwner");
    }

    /** Nulls a private static field when it holds an object from the outgoing generation. */
    private void clearStaticIfOutgoing(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            var value = field.get(null);
            if (fromOutgoing(value)) {
                findings.add(owner.getSimpleName() + "." + name + " = " + value.getClass().getName());
                field.set(null, null);
            }
        } catch (InaccessibleObjectException e) {
            findings.add("cannot reach " + owner.getSimpleName() + "." + name
                    + " (add --add-opens java.desktop/java.awt=ALL-UNNAMED)");
        } catch (ReflectiveOperationException | RuntimeException e) {
            findings.add("failed to clear " + owner.getSimpleName() + "." + name + ": " + e);
        }
    }

    /**
     * Appenders attached to the root logger.
     *
     * <p>logback's {@code LoggerContext} lives on the application classloader for the life of the JVM,
     * so an appender class from the layer stays reachable from a permanent root - and unlike an event
     * subscriber there is nothing in the Solace object graph pointing at it, which makes this
     * invisible to every other check here.
     */
    private void checkLogAppenders() {
        var root = org.slf4j.LoggerFactory.getLogger(
                ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        if (!(root instanceof ch.qos.logback.classic.Logger)) {
            return;
        }
        var logbackRoot = (ch.qos.logback.classic.Logger) root;

        var stale = new ArrayList<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>>();
        for (var it = logbackRoot.iteratorForAppenders(); it.hasNext(); ) {
            ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = it.next();
            if (fromOutgoing(appender)) {
                findings.add("logback appender " + appender.getClass().getName());
                stale.add(appender);
            }
        }
        for (var appender : stale) {
            logbackRoot.detachAppender(appender);
            try {
                appender.stop();
            } catch (RuntimeException e) {
                findings.add("  (failed to stop " + appender.getClass().getName() + ": " + e + ")");
            }
        }
    }

    /**
     * Flushes JVM and RuneLite caches keyed by {@code Class}, which hold classes from the outgoing
     * generation without any Solace code being involved.
     *
     * <p>{@link java.beans.Introspector} is the classic Swing classloader leak: it caches a
     * {@code BeanInfo} per component class, and the layer contributes a lot of Swing panels.
     * {@code ReflectUtil} caches Guice annotation data per injector, and each generation builds its
     * own child injector.
     */
    private static void flushClassKeyedCaches() {
        java.beans.Introspector.flushCaches();
        try {
            // NPEs when nothing was queued via queueInjectorAnnotationCacheInvalidation, which is the
            // normal case here - the queue belongs to RuneLite's own plugin loading, not ours.
            net.runelite.client.util.ReflectUtil.invalidateAnnotationCaches();
        } catch (NullPointerException e) {
            // Nothing queued; nothing to invalidate.
        }
    }

    /** Blocks until everything already queued on the EDT has run. */
    private static void drainEventQueue() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            // Interrupted or the EDT is gone; the checks below are still worth running.
        }
    }

    private void safely(String name, Runnable check) {
        try {
            check.run();
        } catch (Throwable e) {
            findings.add("check '" + name + "' failed: " + e);
        }
    }

    private boolean fromOutgoing(Object target) {
        return target != null && target.getClass().getClassLoader() == outgoing;
    }

    // -- checks --------------------------------------------------------------------------------

    private void checkEventBus() {
        var eventBus = RuneLite.getInjector().getInstance(EventBus.class);
        var subscribers = field(eventBus, "subscribers");
        if (!(subscribers instanceof com.google.common.collect.Multimap)) {
            findings.add("eventBus: could not read subscribers (" + subscribers + ")");
            return;
        }

        // Unregister by OWNER, not by Subscriber: RuneLite's public API is unregister(Object), and it
        // strips every subscriber belonging to that object in one call.
        var staleOwners = new java.util.LinkedHashSet<>();
        for (var entry : ((com.google.common.collect.Multimap<?, ?>) subscribers).values()) {
            var owner = field(entry, "object");
            if (fromOutgoing(owner)) {
                findings.add("eventBus subscriber " + owner.getClass().getName()
                        + "#" + methodName(field(entry, "method")));
                staleOwners.add(owner);
            }
        }

        for (var owner : staleOwners) {
            try {
                eventBus.unregister(owner);
            } catch (RuntimeException e) {
                findings.add("  (failed to unregister " + owner.getClass().getName() + ": " + e + ")");
            }
        }
    }

    private void checkScheduler() {
        var scheduler = RuneLite.getInjector().getInstance(Scheduler.class);
        var methods = scheduler.getScheduledMethods();
        for (var scheduled : new ArrayList<>(methods)) {
            if (fromOutgoing(scheduled.getObject())) {
                findings.add("scheduled method " + scheduled.getObject().getClass().getName());
                scheduler.removeScheduledMethod(scheduled);
            }
        }
    }

    private void checkOverlays() {
        var manager = RuneLite.getInjector().getInstance(OverlayManager.class);
        var overlays = field(manager, "overlays");
        if (overlays instanceof Collection) {
            for (var overlay : new ArrayList<>((Collection<?>) overlays)) {
                if (fromOutgoing(overlay)) {
                    findings.add("overlay " + overlay.getClass().getName());
                }
            }
        }
        manager.removeIf(overlay -> overlay.getClass().getClassLoader() == outgoing);
    }

    private void checkNavButtons() {
        var toolbar = RuneLite.getInjector().getInstance(ClientToolbar.class);
        var ui = field(toolbar, "clientUI");
        var entries = ui == null ? null : field(ui, "sidebarEntries");
        if (!(entries instanceof Collection)) {
            return;
        }
        for (var button : new ArrayList<>((Collection<?>) entries)) {
            var panel = field(button, "panel");
            if (fromOutgoing(panel)) {
                findings.add("nav button for " + panel.getClass().getName());
                try {
                    toolbar.removeNavigation((net.runelite.client.ui.NavigationButton) button);
                } catch (RuntimeException e) {
                    findings.add("  (failed to remove: " + e + ")");
                }
            }
        }
    }

    private void checkInputListeners() {
        var keys = RuneLite.getInjector().getInstance(KeyManager.class);
        for (var listener : listenersOf(keys, "keyListeners")) {
            findings.add("key listener " + listener.getClass().getName());
            keys.unregisterKeyListener((net.runelite.client.input.KeyListener) listener);
        }

        var mouse = RuneLite.getInjector().getInstance(MouseManager.class);
        for (var listener : listenersOf(mouse, "mouseListeners")) {
            findings.add("mouse listener " + listener.getClass().getName());
            mouse.unregisterMouseListener((net.runelite.client.input.MouseListener) listener);
        }
    }

    /**
     * RuneLite's ClientThread queues invocations in a list; anything still pending holds a lambda
     * defined in the outgoing generation.
     */
    private void checkClientThread() {
        var clientThread = RuneLite.getInjector().getInstance(ClientThread.class);
        var queue = field(clientThread, "invokes");
        if (queue instanceof Collection && !((Collection<?>) queue).isEmpty()) {
            for (var pending : new ArrayList<>((Collection<?>) queue)) {
                if (fromOutgoing(pending)) {
                    findings.add("pending client-thread invoke " + pending.getClass().getName());
                }
            }
        }
    }

    /**
     * Attributes a thread by the classloader of the classes on its stack, not by its context
     * classloader - a looped-plugin thread is created on the EDT and inherits the EDT's TCCL, so the
     * context loader says nothing useful.
     */
    private void checkThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            var thread = entry.getKey();
            if (!thread.isAlive()) {
                continue;
            }
            for (var frame : entry.getValue()) {
                if (loadedByOutgoing(frame.getClassName())) {
                    findings.add("thread '" + thread.getName() + "' still running "
                            + frame.getClassName() + "#" + frame.getMethodName());
                    break;
                }
            }
        }
    }

    private boolean loadedByOutgoing(String className) {
        if (!className.startsWith("net.solace.")) {
            return false;
        }
        try {
            return Class.forName(className, false, outgoing).getClassLoader() == outgoing;
        } catch (Throwable e) {
            return false;
        }
    }

    // -- reflection helpers --------------------------------------------------------------------

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the superclass.
            }
        }
        return null;
    }

    private List<Object> listenersOf(Object manager, String fieldName) {
        var value = field(manager, fieldName);
        var stale = new ArrayList<>();
        if (value instanceof Collection) {
            for (var listener : new ArrayList<>((Collection<?>) value)) {
                if (fromOutgoing(listener)) {
                    stale.add(listener);
                }
            }
        }
        return stale;
    }

    private static String methodName(Object method) {
        if (method instanceof java.lang.reflect.Method) {
            return ((java.lang.reflect.Method) method).getName();
        }
        return String.valueOf(method);
    }
}
