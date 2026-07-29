package net.solace.impl.domain.game;

import com.google.inject.Provider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.Client;
import net.runelite.api.FriendContainer;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.solace.api.containers.NpcContainer;
import net.solace.api.containers.PlayerContainer;
import net.solace.api.containers.TileContainer;
import net.solace.api.domain.SceneEntity;
import net.solace.api.domain.actors.INPC;
import net.solace.api.domain.actors.IPlayer;
import net.solace.api.domain.game.IClient;
import net.solace.api.domain.game.IClientThread;
import net.solace.api.domain.tiles.ITile;
import net.solace.impl.containers.ShipContainer;
import net.solace.impl.domain.tiles.TileImpl;
import net.solace.api.domain.widgets.IWidget;
import net.solace.impl.domain.widgets.WidgetImpl;
import net.solace.api.interact.AutomatedMenu;
import net.solace.api.interact.AutomatedMouse;
import net.solace.api.interact.InteractManager;
import net.solace.api.interact.RunnableAction;
import net.solace.api.interact.SleepAction;
import net.solace.impl.reflection.ReflectionManager;
import org.jetbrains.annotations.Nullable;

import java.awt.Canvas;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@RequiredArgsConstructor
@Slf4j
public class ClientImpl implements IClient {
    /** {@code AutomatedMenu}'s default, meaning "caller did not specify a world view". */
    private static final int TOP_LEVEL_WORLD_VIEW_ID = 0;

    private final Client wrapped;
    private final Provider<IClientThread> clientThreadProvider;
    private final Provider<InteractManager> interactManager;
    private final Provider<NpcContainer> npcContainerProvider;
    private final Provider<PlayerContainer> playerContainerProvider;
    private final Provider<TileContainer> tileContainerProvider;
    private final Provider<ShipContainer> shipContainerProvider;

    @Override
    public String getBuildId() {
        return wrapped.getBuildID();
    }

    @Override
    public void interact(AutomatedMenu automatedMenu) {
        interactManager.get().queue(automatedMenu);
    }

    @Override
    public IPlayer getLocalPlayer() {
        return getPlayerContainer().getLocalPlayer();
    }

    @Override
    public String[] getPlayerOptions() {
        return wrapped.getPlayerOptions();
    }

    @Override
    public ObjectComposition getObjectComposition(int id) {
        return clientThreadProvider.get().invokeAndWait(() -> wrapped.getObjectDefinition(id));
    }

    @Override
    public boolean isClientThread() {
        return wrapped.isClientThread();
    }

    @Override
    public ItemComposition getItemComposition(int id) {
        return clientThreadProvider.get().invokeAndWait(() -> wrapped.getItemDefinition(id));
    }

    @Override
    public ItemContainer getItemContainer(int id) {
        return clientThreadProvider.get().invokeAndWait(() -> wrapped.getItemContainer(id));
    }

    @Override
    public ItemContainer getItemContainer(InventoryID id) {
        return clientThreadProvider.get().invokeAndWait(() -> wrapped.getItemContainer(id));
    }

    @Override
    public WorldView getTopLevelWorldView() {
        return wrapped.getTopLevelWorldView();
    }

    @Override
    public ITile[][][] getTiles() {
        return tileContainerProvider.get().getAllFloors();
    }

    @Override
    public int getPlane() {
        return getTopLevelWorldView().getPlane();
    }

    @Override
    public IWidget[] getWidgets(int group) {
        var clientThread = clientThreadProvider.get();
        return clientThread.invokeAndWait(() -> {
            if (group < 0) {
                return null;
            }

            var widgetsClassInstance = ReflectionManager.getStatic("widgets");
            Widget[][] widgetArray = ReflectionManager.getField(widgetsClassInstance, "Widgets", "Widget_interfaceComponents");
            if (widgetArray == null) {
                return null;
            }

            if (group >= widgetArray.length) {
                return null;
            }

            var widgetGroup = widgetArray[group];
            if (widgetGroup == null) {
                return null;
            }
            var out = new IWidget[widgetGroup.length];
            for (int i = 0; i < widgetGroup.length; i++) {
                var widget = widgetGroup[i];
                if (widget != null) {
                    out[i] = WidgetImpl.of(widget, this, clientThread);
                }
            }

            return out;
        });
    }

    @Override
    public IWidget getWidget(int group, int id) {
        var rlWidget = wrapped.getWidget(group, id);
        return rlWidget == null ? null : WidgetImpl.of(rlWidget, this, clientThreadProvider.get());
    }

    @Override
    public IWidget getWidget(int componentId) {
        var rlWidget = wrapped.getWidget(componentId);
        return rlWidget == null ? null : WidgetImpl.of(rlWidget, this, clientThreadProvider.get());
    }

    @Override
    public IPlayer getHintArrowPlayer() {
        return getPlayerContainer().getHintArrowed();
    }

    @Override
    public WorldPoint getHintArrowPosition() {
        var player = getHintArrowPlayer();
        if (player != null) {
            return player.getWorldLocation();
        }

        var npc = getHintArrowNpc();
        if (npc != null) {
            return npc.getWorldLocation();
        }

        return null;
    }

    @Override
    public int[] getVarps() {
        return wrapped.getVarps();
    }

    @Override
    public int getVarbitValue(int[] vars, int id) {
        return clientThreadProvider.get().invokeAndWait(() -> wrapped.getVarbitValue(vars, id));
    }

    @Override
    public int getVarpValue(int id) {
        return wrapped.getVarpValue(id);
    }

    @Override
    public int getVarcIntValue(int id) {
        return wrapped.getVarcIntValue(id);
    }

    @Override
    public String getVarcStrValue(int id) {
        return wrapped.getVarcStrValue(id);
    }

    @Override
    public GameState getGameState() {
        return wrapped.getGameState();
    }

    @Override
    public void setGameState(GameState gameState) {
        wrapped.setGameState(gameState);
    }

    @Override
    public void setGameState(int gameState) {
        wrapped.setGameState(GameState.of(gameState));
    }

    @Override
    public int getTickCount() {
        return wrapped.getTickCount();
    }

    @Override
    public void runScript(Object... args) {
        interactManager.get().queue(new RunnableAction(() -> wrapped.runScript(args)));
    }

    @Override
    public int getBoostedSkillLevel(Skill skill) {
        return wrapped.getBoostedSkillLevel(skill);
    }

    @Override
    public int getRealSkillLevel(Skill skill) {
        return wrapped.getRealSkillLevel(skill);
    }

    @Override
    public int getSkillExperience(Skill skill) {
        return wrapped.getSkillExperience(skill);
    }

    @Override
    public boolean isInInstancedRegion() {
        return getTopLevelWorldView().isInstance();
    }

    @Override
    public World[] getWorldList() {
        return wrapped.getWorldList();
    }

    @Override
    public int getWorld() {
        return wrapped.getWorld();
    }

    @Override
    public void openWorldHopper() {
        wrapped.openWorldHopper();
    }

    @Override
    public void changeWorld(World world) {
        wrapped.changeWorld(world);
    }

    @Override
    public void hopToWorld(World world) {
        wrapped.hopToWorld(world);
    }

    @Override
    public Canvas getCanvas() {
        return wrapped.getCanvas();
    }

    @Override
    public void setVarcIntValue(int var, int value) {
        wrapped.setVarcIntValue(var, value);
    }

    @Override
    public void setVarcStrValue(int var, String value) {
        wrapped.setVarcStrValue(var, value);
    }

    @Override
    public GrandExchangeOffer[] getGrandExchangeOffers() {
        return wrapped.getGrandExchangeOffers();
    }

    @Override
    public int[] getMapRegions() {
        return wrapped.getMapRegions();
    }

    @Override
    public EnumSet<WorldType> getWorldType() {
        return wrapped.getWorldType();
    }

    @Override
    public int[] getIntStack() {
        return wrapped.getIntStack();
    }

    @Override
    public int getEnergy() {
        return wrapped.getEnergy();
    }

    @Override
    public FriendContainer getFriendContainer() {
        return wrapped.getFriendContainer();
    }

    @Override
    public boolean isFriended(String name, boolean mustBeLoggedIn) {
        return wrapped.isFriended(name, mustBeLoggedIn);
    }

    @Override
    public int getViewportHeight() {
        return wrapped.getViewportHeight();
    }

    @Override
    public int getViewportWidth() {
        return wrapped.getViewportWidth();
    }

    @Override
    public boolean isResized() {
        return wrapped.isResized();
    }

    @Override
    public int getMapAngle() {
        return wrapped.getCameraYawTarget();
    }

    @Override
    public WorldMap getWorldMap() {
        return wrapped.getWorldMap();
    }

    @Override
    public World createWorld() {
        return wrapped.createWorld();
    }

    @Override
    public void setSelectedSceneTileX(int sceneX) {
        ReflectionManager.putField(getTopLevelWorldView().getScene(), "Scene", "selectedX", sceneX);
    }

    @Override
    public void setSelectedSceneTileY(int sceneY) {
        ReflectionManager.putField(getTopLevelWorldView().getScene(), "Scene", "selectedY", sceneY);
    }

    @Override
    public void setViewportWalking(boolean b) {
        ReflectionManager.putField(getTopLevelWorldView().getScene(), "Scene", "viewportWalking", b);
    }

    @Override
    public void setCheckClick(boolean b) {
        // No longer backed by a reflection hook. Nothing in Solace calls this, and a synthetic click
        // makes the client set the flag itself. Kept as a no-op rather than deleted because this is
        // published SDK API - see setLastButton for the same reasoning.
        log.debug("setCheckClick({}) ignored: not supported without a reflection hook", b);
    }

    @Override
    public LocalPoint getLocalDestinationLocation() {
        return wrapped.getLocalDestinationLocation();
    }

    @Override
    public void setLastButton(int i) {
        // No longer backed by a reflection hook. Nothing in Solace calls this, RuneLite exposes no
        // setter for the mouse button (only getMouseCurrentButton), and a synthetic MouseEvent carries
        // its own button - so forcing the field served no purpose. Kept as a no-op rather than removed
        // because it is published SDK API and deleting it would break external scripts that reference it.
        log.debug("setLastButton({}) ignored: not supported without a reflection hook", i);
    }

    @Nullable
    @Override
    public INPC getFollower() {
        return getNpcContainer().getFollower();
    }

    @Override
    public INPC getHintArrowNpc() {
        return getNpcContainer().getHintArrowed();
    }

    public void invokeWidgetAction(int identifier, int widgetId, int param0, int itemId, String target) {
        invokeWidgetAction(identifier, widgetId, param0, itemId, target, "");
    }

    @Override
    public void invokeWidgetAction(int identifier, int widgetId, int param0, int itemId, String target, String option) {
        // Was ReflectionManager.invokeStatic("widgetDefaultMenuAction", ...). RuneLite publishes
        // menuAction(...), and a widget's default op is CC_OP, so the hook is redundant: param0 is the
        // child index, param1 the packed component id, identifier the op index.
        clientThreadProvider.get().invoke(() -> wrapped.menuAction(
                param0, widgetId, MenuAction.CC_OP, identifier, itemId, option, target));
    }

    /**
     * Always empty: no public API exposes the login screen's response text.
     *
     * <p>This never worked on this revision anyway - the concatenation read {@code loginResponse2} and
     * {@code loginResponse3}, which were not in the mappings at all, so every call threw. Returning
     * empty rather than null is load-bearing: {@code LoginEvent.loop()} calls {@code toLowerCase()} on
     * the result unguarded, so null would swap a throw for an NPE.
     *
     * <p>The cost is login failure diagnostics. {@code getLoginIndex()} is public and still reports the
     * state (INVALID_CREDENTIALS, DISABLED, MEMBERS_REQUIRED), just not the server's wording.
     */
    @Override
    public String getLoginMessage() {
        return "";
    }

    @Override
    public void setLoginIndex(int i) {
        ReflectionManager.putStatic("loginIndex", i);
    }

    // setMouseIdleTicks / setMouseIdleCycles / setKeyboardIdleTicks are gone. They existed only to
    // hold off the idle logout during a no-logout break by resetting the counters every tick. The
    // public setIdleTimeout() raises the threshold those counters are compared against, which is the
    // same outcome with one write instead of one per tick - see SolaceBreakHandlerPlugin.
    // The reads getMouseIdleTicks()/getKeyboardIdleTicks() are public RuneLite API and remain.

    @Override
    public boolean loadWorlds() {
        return ReflectionManager.invokeStatic("loadWorlds");
    }

    @Override
    public void promptCredentials(boolean b) {
        ReflectionManager.invokeStatic("Login_promptCredentials", b);
    }

    @Override
    public boolean isWorldSelectOpen() {
        return ReflectionManager.getStatic("worldSelectOpen");
    }

    @Override
    public void setWorldSelectOpen(boolean b) {
        ReflectionManager.putStatic("worldSelectOpen", b);
    }

    @Override
    public boolean isOAuthCredentialsSet() {
        String sessionId = ReflectionManager.getStatic("sessionId");
        String characterId = ReflectionManager.getStatic("characterId");
        return sessionId != null && !sessionId.trim().isEmpty()
                && characterId != null && !characterId.trim().isEmpty();
    }

    // getPassword() is deliberately absent: RuneLite exposes no password getter, and the client was
    // never the source of truth anyway - Solace pushes credentials in. AccountManager holds them
    // encrypted, and Game.getGameAccount().getPassword() is what every real caller already reads.

    @Override
    public void setPassword(String s) {
        wrapped.setPassword(s);
    }

    @Override
    public String getUsername() {
        return wrapped.getUsername();
    }

    @Override
    public void setUsername(String s) {
        wrapped.setUsername(s);
    }

    @Override
    public void setOtp(String s) {
        wrapped.setOtp(s);
    }

    @Override
    public int getWindowedMode() {
        return isResized() ? 2 : 1;
    }

    @Override
    public void setWindowedMode(int i) {
        runScript(3998, i);
    }

    @Override
    public List<? extends SceneEntity> getHoveredEntities() {
        var menuEntries = wrapped.getMenu().getMenuEntries();
        if (menuEntries.length == 0) {
            return Collections.emptyList();
        }

        Set<SceneEntity> out = new HashSet<>();

        for (var menuEntry : menuEntries) {
            var ship = shipContainerProvider.get().getShips().get(menuEntry.getWorldViewId());
            var menuAction = menuEntry.getType();
            switch (menuAction) {
                case EXAMINE_OBJECT:
                case WIDGET_TARGET_ON_GAME_OBJECT:
                case GAME_OBJECT_FIRST_OPTION:
                case GAME_OBJECT_SECOND_OPTION:
                case GAME_OBJECT_THIRD_OPTION:
                case GAME_OBJECT_FOURTH_OPTION:
                case GAME_OBJECT_FIFTH_OPTION:
                    var objX = menuEntry.getParam0();
                    var objY = menuEntry.getParam1();
                    var cachedTiles1 = ship != null ? ship.getTileManager().getRawFloors() :
                            getTileContainer().getAllFloors();

                    if (ship == null) {
                        var tile = cachedTiles1[getPlane()][objX][objY];
                        var iGameObjects = tile.getTileObjects();
                        out.addAll(iGameObjects);
                        break;
                    }

                    for (ITile[][] iTiles : cachedTiles1) {
                        var tile = iTiles[objX][objY];
                        if (tile == null) {
                            continue;
                        }

                        var iGameObjects = tile.getTileObjects();
                        out.addAll(iGameObjects);
                    }

                    break;
                case EXAMINE_NPC:
                case WIDGET_TARGET_ON_NPC:
                case NPC_FIRST_OPTION:
                case NPC_SECOND_OPTION:
                case NPC_THIRD_OPTION:
                case NPC_FOURTH_OPTION:
                case NPC_FIFTH_OPTION:
                    var npcIdx = menuEntry.getIdentifier();
                    var cachedNpc = ship != null ? ship.getNpcManager().get(npcIdx)
                            : getNpcContainer().get(npcIdx);
                    out.add(cachedNpc);
                    break;
                case EXAMINE_ITEM_GROUND:
                case WIDGET_TARGET_ON_GROUND_ITEM:
                case GROUND_ITEM_FIRST_OPTION:
                case GROUND_ITEM_SECOND_OPTION:
                case GROUND_ITEM_THIRD_OPTION:
                case GROUND_ITEM_FOURTH_OPTION:
                case GROUND_ITEM_FIFTH_OPTION:
                    var itemX = menuEntry.getParam0();
                    var itemY = menuEntry.getParam1();
                    var cachedTiles2 = ship != null ? ship.getTileManager().getRawFloors() :
                            getTileContainer().getAllFloors();

                    if (ship == null) {
                        var tile = cachedTiles2[getPlane()][itemX][itemY];
                        var items = tile.getIGroundItems();
                        out.addAll(items);
                        break;
                    }

                    for (ITile[][] iTiles : cachedTiles2) {
                        var tile = iTiles[itemX][itemY];
                        if (tile == null) {
                            continue;
                        }

                        var items = tile.getIGroundItems();
                        out.addAll(items);
                    }
                    break;
                case WIDGET_TARGET_ON_PLAYER:
                case PLAYER_FIRST_OPTION:
                case PLAYER_SECOND_OPTION:
                case PLAYER_THIRD_OPTION:
                case PLAYER_FOURTH_OPTION:
                case PLAYER_FIFTH_OPTION:
                case PLAYER_SIXTH_OPTION:
                case PLAYER_SEVENTH_OPTION:
                case PLAYER_EIGHTH_OPTION:
                    var playerIdx = menuEntry.getIdentifier();
                    var cachedPlayer = ship != null ? ship.getPlayerManager().get(playerIdx)
                            : getPlayerContainer().get(playerIdx);

                    out.add(cachedPlayer);
                    break;
            }
        }

        return new ArrayList<>(out);
    }

    @Override
    public ITile getSelectedSceneTile() {
        return TileImpl.of(wrapped.getSelectedSceneTile(), this);
    }

    @Override
    public void setDraggedWidget(Widget widget) {
        ReflectionManager.putStatic("clickedWidget", widget);
    }

    @Override
    public int getMinimapState() {
        // The minimap-state hook is gone. It only ever backed an anti-interference trick - disable the
        // minimap around a synthetic click - that easy-rl does without entirely. Published SDK API, so
        // the signature stays; MINIMAP_ENABLED is the honest answer when nothing is toggling it.
        return 0;
    }

    @Override
    public void setMinimapState(int i) {
        log.debug("setMinimapState({}) ignored: not supported without a reflection hook", i);
    }

    @Override
    public void processDialog(int widgetId, int childIndex) {
        // Was ReflectionManager.invokeStatic("resumePauseWidget", ...). Advancing a paused dialogue is
        // MenuAction.WIDGET_CONTINUE against the same widget, which RuneLite's public menuAction can
        // submit, so this no longer needs a hook.
        clientThreadProvider.get().invoke(() -> wrapped.menuAction(
                childIndex, widgetId, MenuAction.WIDGET_CONTINUE, 0, -1, "Continue", ""));
    }

    @Override
    public void setMenuOpened(boolean b) {
        ReflectionManager.putStatic("isMenuOpen", b);
    }

    @Override
    public void interact(int x, int y, boolean click) {
        interactManager.get().queue(new AutomatedMouse(x, y, click));
    }

    @Override
    public void setOAuthLoginMode() {
        var oAuthLoginMode = ReflectionManager.getStatic("oAuthLoginMode");
        ReflectionManager.putStatic("loginMode", oAuthLoginMode);
    }

    @Override
    public void setNormalLoginMode() {
        var normalLoginMode = ReflectionManager.getStatic("normalLoginMode");
        ReflectionManager.putStatic("loginMode", normalLoginMode);
    }

    @Override
    public String getDisplayName() {
        return ReflectionManager.getStatic("displayName");
    }

    @Override
    public void setDisplayName(String s) {
        ReflectionManager.putStatic("displayName", s);
    }

    @Override
    public String getSessionId() {
        return ReflectionManager.getStatic("sessionId");
    }

    @Override
    public void setSessionId(String s) {
        ReflectionManager.putStatic("sessionId", s);
    }

    @Override
    public String getCharacterId() {
        return ReflectionManager.getStatic("characterId");
    }

    @Override
    public void setCharacterId(String s) {
        ReflectionManager.putStatic("characterId", s);
    }

    @Override
    public PlayerContainer getPlayerContainer() {
        return playerContainerProvider.get();
    }

    @Override
    public NpcContainer getNpcContainer() {
        return npcContainerProvider.get();
    }

    @Override
    public TileContainer getTileContainer() {
        return tileContainerProvider.get();
    }

    @Override
    public void sleep(int cycles) {
        var mgr = interactManager.get();
        for (int i = 0; i < cycles; i++) {
            mgr.queue(new SleepAction());
        }
    }

    @Override
    public String getDiscordId() {
        return null;
    }

    @Override
    public String getDiscordUser() {
        return "user";
    }

    @Override
    public Long getUserId() {
        return 1L;
    }

    /**
     * Invokes a menu action through RuneLite's public {@code menuAction}, which removes the last
     * reflection dependency from the interaction path.
     *
     * <p>The public overload has no {@code worldViewId} parameter, so a non-top-level world view
     * cannot be expressed. That is warned about rather than silently dropped: a sailing interaction
     * that quietly targets the wrong world view is worse than a loud one.
     */
    @Override
    public void invokeMenuAction(int param0, int param1, int opcode, int identifier, int itemId,
                                int worldViewId, String option, String target) {
        var action = MenuAction.of(opcode);
        if (action == MenuAction.UNKNOWN) {
            log.warn("Refusing to invoke unknown opcode {} (option='{}', target='{}')", opcode, option, target);
            return;
        }
        if (isSyntheticOpcode(opcode)) {
            log.warn("Refusing to invoke client-side opcode {} ({})", opcode, action);
            return;
        }
        if (worldViewId != TOP_LEVEL_WORLD_VIEW_ID && worldViewId != wrapped.getTopLevelWorldView().getId()) {
            log.warn("worldViewId {} cannot be passed through the public menuAction; invoking against"
                    + " the top level world view instead (opcode={}, option='{}')", worldViewId, opcode, option);
        }
        wrapped.menuAction(param0, param1, action, identifier, itemId, option, target);
    }

    /**
     * True for opcodes RuneLite synthesises client-side. They have no server packet, so invoking them
     * does nothing useful and masks the real bug.
     */
    private static boolean isSyntheticOpcode(int opcode) {
        return opcode == 998 || opcode == 999 || opcode >= 1500;
    }

    @Override
    public net.solace.api.rs.MouseRecorder getMouseRecorder() {
        return new net.solace.api.rs.MouseRecorder();
    }

    @Override
    public boolean isFocused() {
        var canvas = wrapped.getCanvas();
        return canvas != null && canvas.hasFocus();
    }

    @Override
    public void setFocused(boolean focused) {
        var canvas = wrapped.getCanvas();
        if (canvas != null && focused) {
            canvas.requestFocus();
        }
    }
}
