package net.solace.loader;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.solace.api.containers.NpcContainer;
import net.solace.api.containers.PlayerContainer;
import net.solace.api.containers.TileContainer;
import net.solace.api.game.GameStateManager;
import net.solace.api.game.IVars;
import net.solace.api.interact.InteractManager;
import net.solace.api.items.IBank;
import net.solace.api.items.IBankInventory;
import net.solace.api.items.IEquipment;
import net.solace.api.items.IInventory;
import net.solace.api.items.ITradeInventory;
import net.solace.api.items.ITradeOther;
import net.solace.api.items.ITradeOurs;
import net.solace.api.items.loadouts.LoadoutManager;
import net.solace.api.plugins.PluginManager;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.api.plugins.exception.PluginInstantiationException;
import net.solace.api.quests.IQuests;
import io.reactivex.rxjava3.schedulers.Schedulers;
import net.solace.api.commons.OwnedExecutors;
import net.solace.impl.containers.ShipContainer;
import net.solace.impl.movement.WalkerManager;
import net.solace.impl.reflection.ReflectionManager;
import net.solace.loader.events.EventManager;
import net.solace.loader.hotswap.DevPluginHotSwapHolder;
import net.solace.loader.local.LocalBootstrap;
import net.solace.loader.plugins.LoopedPluginManager;
import net.solace.loader.plugins.config.ConfigManagerImpl;
import net.solace.loader.local.LocalVersionPackageLoader;
import net.solace.loader.thirdparty.EternalFarmCompat;
import net.solace.loader.thirdparty.IncompatiblePluginChecker;
import net.solace.loader.ui.SolaceUI;
import net.solace.sdn.plugins.version.VersionPackage;
import net.solace.ui.plugins.ProfilePanel;

import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

@Slf4j
@Singleton
public class SolaceInitializer implements SolaceManager {
    /** How long to wait for looped-plugin threads to die before declaring teardown failed. */
    private static final long QUIESCE_MILLIS = 3000;

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private ConfigManager configManager;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private net.runelite.client.plugins.PluginManager rlPluginManager;

    @Inject
    private SolaceUI solaceUI;

    @Inject
    private InteractManager interactManager;

    @Inject
    private EventManager eventManager;

    @Inject
    private ReflectionManager reflectionManager;

    @Inject
    private WalkerManager walkerManager;

    @Inject
    private IVars vars;

    @Inject
    private LocalBootstrap localBootstrap;

    @Inject
    private NpcContainer npcContainer;

    @Inject
    private TileContainer tileContainer;

    @Inject
    private PlayerContainer playerContainer;

    @Inject
    private IInventory inventory;

    @Inject
    private IEquipment equipment;

    @Inject
    private IBank bank;

    @Inject
    private IBankInventory bankInventory;

    @Inject
    private ITradeInventory tradeInventory;

    @Inject
    private ITradeOther tradeOther;

    @Inject
    private ITradeOurs tradeOurs;

    @Inject
    private IQuests quests;

    @Inject
    private GameStateManager gameStateManager;

    @Inject
    private ProfilePanel profilePanel;

    @Inject
    private LoadoutManager loadoutManager;

    @Inject
    private IncompatiblePluginChecker incompatiblePluginChecker;

    @Inject
    private ShipContainer shipContainer;

    @Inject
    @Named("ef")
    @Nullable
    private String eternalFarmArg;

    /** Non-daemon, so it keeps the JVM alive and pins this generation unless shut down explicitly. */
    @Inject
    @Named("shutDownExecutor")
    private ExecutorService shutDownExecutor;

    /** The {@code worker-%d} pool. Also non-daemon. */
    @Inject
    private ExecutorService workerExecutor;

    @Override
    public void start() throws Exception {
        start(true);
    }

    /**
     * @param firstInit false on a hot reload. Guards the steps that mutate RuneLite state which
     *                  survives a reload - RuneLite's plugin manager has no dedupe, so re-running
     *                  {@code loadSideLoadPlugins()} loads every sideloaded jar a second time into a
     *                  second classloader and appends duplicate Plugin instances, silently.
     */
    @Override
    public void start(boolean firstInit) throws Exception {
        if (firstInit) {
            incompatiblePluginChecker.checkAndDisable();
        }
        onVersionPackageReady(LocalVersionPackageLoader.load(), firstInit);
    }

    private void onVersionPackageReady(VersionPackage versionPackage, boolean firstInit) {
        var startTime = System.currentTimeMillis();

        if (!Objects.equals(versionPackage.getRuneLiteCommit(), RuneLiteProperties.getCommit())
                || !Objects.equals(versionPackage.getRuneLiteVersion(), RuneLiteProperties.getVersion())) {
            throw new IllegalStateException("Solace has not yet been updated for this version of RuneLite, please wait for an update." +
                    " If you'd like to use RuneLite normally, please restart the client and select the 'Normal RuneLite' option.");
        }

        try {
            reflectionManager.load(client.getClass().getClassLoader(), versionPackage);
        } catch (Exception e) {
            throw new RuntimeException("Solace failed to initialize.", e);
        }

        configManager.load();

        registerEventManagers();

        solaceUI.init();

        profilePanel.init();

        // First init only. RuneLite's PluginManager does not dedupe, so a second call loads every
        // sideloaded jar again into a second classloader and appends duplicate Plugin instances -
        // which surfaces as doubled overlays and doubled event handling, reading as a Solace bug.
        if (firstInit) {
            try {
                rlPluginManager.loadSideLoadPlugins();
                rlPluginManager.loadDefaultPluginConfiguration(null);
                rlPluginManager.startPlugins();
                eventBus.post(new ExternalPluginsChanged());
            } catch (Exception e) {
                log.error("Error loading side-load plugins", e);
            }
        }

        try {
            pluginManager.loadCorePlugins();
            pluginManager.startCorePlugins();
        } catch (IOException | PluginInstantiationException e) {
            log.error("Error loading core plugins", e);
        }

        localBootstrap.loadPlugins();

        // First init only: EternalFarmCompat spawns an agent from a URLClassLoader parented to this
        // generation, and its threads would pin the generation forever. An --ef run is effectively
        // non-reloadable.
        if (firstInit && eternalFarmArg != null) {
            EternalFarmCompat.init(eternalFarmArg, pluginManager.getClass().getClassLoader());
        }

        log.info("Solace initialized in {} ms", System.currentTimeMillis() - startTime);
    }

    /**
     * Tears Solace down so its classloader generation can be collected.
     *
     * <p>Every step runs in its own {@code try}/{@code catch} and every step is attempted. A step that
     * throws must never skip the ones after it - that is exactly how you get a half-torn-down client,
     * where the game is still running but Solace is in an indeterminate state.
     *
     * @return the failures encountered, empty when teardown was clean
     */
    @Override
    public List<String> unload() {
        var failures = new ArrayList<String>();

        // Dev-plugin hot swap first: its watcher thread and classloader are parented to this
        // generation, so a dev-plugin generation must never outlive the layer it was built against.
        step(failures, "hotswap", DevPluginHotSwapHolder::closeIfPresent);

        step(failures, "stopPlugins", pluginManager::stopPlugins);
        step(failures, "quiesce", this::quiescePluginThreads);

        // PluginManagerImpl registers itself in its constructor (for onProfileChanged) and nothing
        // ever undid it. That single subscriber transitively pins every Plugin instance and every
        // per-plugin Guice child injector.
        step(failures, "unregister pluginManager", () -> eventBus.unregister(pluginManager));

        for (var subscriber : eventSubscribers()) {
            step(failures, "unregister " + subscriber.getClass().getSimpleName(),
                    () -> eventBus.unregister(subscriber));
        }

        // Kills the RxJava scheduler threads. Each one captured an AccessControlContext when it was
        // created, and that context holds a ProtectionDomain referencing THIS classloader - so a
        // surviving Rx thread pins the whole generation for the life of the JVM, invisibly to every
        // other check here. Safe to do process-wide: RuneLite does not ship RxJava (verified - zero
        // io.reactivex classes in its jar), and Solace's only user is the break handler, which
        // stopPlugins() has already shut down above.
        //
        // The bootstrap calls Schedulers.start() again after dropping this loader, so the replacement
        // threads are born outside the layer and capture nothing.
        step(failures, "rxSchedulers", Schedulers::shutdown);

        step(failures, "configManager", this::closeConfigManager);
        step(failures, "externalPlugins", this::unloadExternalPlugins);
        step(failures, "ui", solaceUI::clear);
        step(failures, "executors", OwnedExecutors::shutdownAll);
        step(failures, "shutDownExecutor", shutDownExecutor::shutdownNow);
        step(failures, "workerExecutor", workerExecutor::shutdownNow);

        if (failures.isEmpty()) {
            log.info("Solace unloaded cleanly");
        } else {
            log.warn("Solace unloaded with {} failure(s): {}", failures.size(), failures);
        }
        return failures;
    }

    private void step(List<String> failures, String name, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.warn("Teardown step '{}' failed", name, e);
            failures.add(name + ": " + e);
        }
    }

    /**
     * Waits for looped-plugin threads to actually die. {@code stopPlugins()} sets a stopped flag and
     * interrupts, but the executor only observes it after its current sleep - and two generations of
     * the same plugin driving one client is the most confusing failure this design can produce.
     */
    private void quiescePluginThreads() throws InterruptedException {
        var deadline = System.currentTimeMillis() + QUIESCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (livePluginThreads().isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        var survivors = livePluginThreads();
        if (!survivors.isEmpty()) {
            throw new IllegalStateException(
                    "plugin threads still alive after " + QUIESCE_MILLIS + " ms: " + survivors);
        }
    }

    private static List<String> livePluginThreads() {
        var alive = new ArrayList<String>();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(LoopedPluginManager.THREAD_PREFIX)) {
                alive.add(thread.getName());
            }
        }
        return alive;
    }

    private void closeConfigManager() {
        if (configManager instanceof ConfigManagerImpl) {
            ((ConfigManagerImpl) configManager).close();
        }
    }

    /**
     * pf4j plugin classloaders are parented to this generation, and
     * {@code SdnPluginManagerImpl.pluginClassLoaders} is a static list that only grows.
     */
    private void unloadExternalPlugins() {
        localBootstrap.unloadPlugins();
    }

    private List<Object> eventSubscribers() {
        return List.of(configManager, interactManager, eventManager, walkerManager, vars,
                gameStateManager, loadoutManager, npcContainer, tileContainer, playerContainer,
                inventory, equipment, bank, bankInventory, tradeInventory, tradeOther, tradeOurs,
                quests, shipContainer);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void registerEventManagers() {
        eventManager.init();
        walkerManager.init();

        eventBus.register(configManager);
        eventBus.register(interactManager);
        eventBus.register(eventManager);
        eventBus.register(walkerManager);
        eventBus.register(vars);
        eventBus.register(gameStateManager);
        eventBus.register(loadoutManager);
        eventBus.register(npcContainer);
        eventBus.register(tileContainer);
        eventBus.register(playerContainer);
        eventBus.register(inventory);
        eventBus.register(equipment);
        eventBus.register(bank);
        eventBus.register(bankInventory);
        eventBus.register(tradeInventory);
        eventBus.register(tradeOther);
        eventBus.register(tradeOurs);
        eventBus.register(quests);
        eventBus.register(shipContainer);
    }
}
