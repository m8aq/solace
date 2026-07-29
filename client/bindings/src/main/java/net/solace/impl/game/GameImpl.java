package net.solace.impl.game;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.solace.api.account.GameAccount;
import net.solace.api.domain.game.IClient;
import net.solace.api.game.GameStateManager;
import net.solace.api.game.IGame;
import net.solace.api.game.IVars;
import net.solace.api.widgets.ITabs;
import net.solace.api.domain.widgets.IWidget;
import net.solace.api.widgets.IWidgets;
import net.solace.api.widgets.Tab;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class GameImpl implements IGame {
    private static final int CUTSCENE_VARBIT = 542;
    private static final int LOADING_CUTSCENE_VARBIT = 6719;
    private static final int MEMBER_DAYS_VARP = 1780;
    private static final String LOGOUT_ACTION = "Logout";
    private static final List<Integer> blacklistedCutsceneRegions = Arrays.asList(10307, 14231);
    private final IClient client;
    private final IVars vars;
    private final IWidgets widgets;
    private final ITabs tabs;
    private final GameStateManager gameStateManager;

    @Getter
    @Setter
    private GameAccount gameAccount;

    @Override
    public boolean isLoggedIn() {
        return getState() == GameState.LOGGED_IN || getState() == GameState.LOADING;
    }

    @Override
    public GameState getState() {
        return client.getGameState();
    }

    @Override
    public boolean isInCutscene() {
        return vars.getBit(CUTSCENE_VARBIT) > 0
                || (vars.getBit(LOADING_CUTSCENE_VARBIT) > 0 && Arrays.stream(client.getMapRegions()).noneMatch(blacklistedCutsceneRegions::contains));
    }

    @Override
    public int getWildyLevel() {
        var wildyLevelWidget = widgets.get(InterfaceID.PvpIcons.WILDERNESSLEVEL);
        if (!widgets.isVisible(wildyLevelWidget)) {
            return 0;
        }

        var widgetText = wildyLevelWidget.getText();
        if (widgetText.isEmpty()
                || wildyLevelWidget.getText().contains("Guarded")
                || wildyLevelWidget.getText().contains("Protection")
                || wildyLevelWidget.getText().contains("Deadman")) {
            return 0;
        }
        if (widgetText.equals("Level: --")) {
            var local = client.getLocalPlayer();
            var localLocation = local.getLocalLocation();
            var y = WorldPoint.fromLocal(
                    client.getWrapped().getWorldView(localLocation.getWorldView()),
                    localLocation.getX(),
                    localLocation.getY(),
                    local.getWorldLocation().getPlane()
            ).getY();
            return 2 + (y - 3528) / 8;
        }
        var levelText = widgetText.contains("<br>") ? widgetText.substring(0, widgetText.indexOf("<br>")) : widgetText;
        return Integer.parseInt(levelText.replace("Level: ", ""));
    }

    @Override
    public boolean isInWilderness() {
        return vars.getBit(VarbitID.INSIDE_WILDERNESS) == 1;
    }

    @Override
    public int getDeadmanLevel() {
        var wildyLevelWidget = widgets.get(InterfaceID.PvpIcons.WILDERNESSLEVEL);
        if (wildyLevelWidget.getText().contains("Guarded")
                || wildyLevelWidget.getText().contains("Protection")) {
            return 0;
        }

        if (wildyLevelWidget.getText().contains("Deadman")) {
            return Integer.MAX_VALUE;
        }

        return 0;
    }

    @Override
    public int getMembershipDays() {
        return vars.getVarp(MEMBER_DAYS_VARP);
    }

    @Override
    public boolean isBlackScreen() {
        var blackScreen = widgets.get(174, 0);

        return widgets.isVisible(blackScreen);
    }

    /**
     * Logs out, one step per call: open the logout panel, then click its button.
     *
     * <p>The button is resolved by scanning group 182 for the {@code Logout} action rather than by
     * component id. {@code ComponentID.LOGOUT_PANEL_LOGOUT_BUTTON} names child 6, which on this
     * revision is a type-5 sprite with no actions at all - the action lives on child 8. Group 182
     * also holds a second {@code Logout}-like entry (the world switcher's), so the scan takes the
     * exact match.
     *
     * <p>The op is invoked directly instead of through a synthetic click. The click path dispatches
     * at the widget's screen bounds and was observed doing nothing here, whereas invokeWidgetAction
     * posts the menu action itself - the same route the welcome-screen play button already uses.
     *
     * <p>Every component in the panel reports hidden while the tab is shut, so a cold call can only
     * open the tab; the click is possible from the next call onward. {@code
     * LoginCommandService.logout} drives that to completion.
     */
    @Override
    public void logout() {
        var button = widgets.get(InterfaceID.LOGOUT, x -> !x.isHidden() && x.hasAction(LOGOUT_ACTION));
        if (button != null) {
            client.invokeWidgetAction(actionIndex(button, LOGOUT_ACTION), button.getId(), -1, -1, "", "");
            return;
        }

        if (!tabs.isOpen(Tab.LOG_OUT)) {
            tabs.open(Tab.LOG_OUT);
        }
    }

    /** One-based op index of an action, which is what menuAction expects. */
    private static int actionIndex(IWidget widget, String action) {
        var actions = widget.getActions();
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                if (action.equalsIgnoreCase(actions[i])) {
                    return i + 1;
                }
            }
        }
        return 1;
    }

    @Override
    public Instant getLastLogin() {
        return gameStateManager.getLastLogin();
    }
}
