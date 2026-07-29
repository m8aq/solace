package net.solace.loader.plugins.menurecorder;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.widgets.Widget;

/**
 * Records every menu entry the client builds, resolves it back to (entity class, op slot), and checks
 * whether the ladder in {@link Ladders} predicts the opcode the client actually used.
 *
 * <p>This is the observation half of moving interaction onto {@code menuAction}: the ladders are known
 * from {@code MenuAction}, but what the params and identifier actually carry <em>in this client build</em>
 * is not. Right-clicking is the cheap way to find out, because one right-click emits the whole op array
 * at once instead of the single entry a click would.
 *
 * <p>Not a plugin. Owned by {@code SolaceDevToolsPlugin} and driven by its "Log menu interactions"
 * toggle, so menu recording sits with the rest of the interaction debugging rather than in a separate
 * plugin the operator has to know to enable.
 *
 * <p>Output: {@code ~/.solace/menu-recorder/observations.jsonl} and {@code summary.json}.
 */
@Slf4j
public class MenuRecorder {
    private final Client client;

    private final RecorderStore store = new RecorderStore();

    public MenuRecorder(Client client) {
        this.client = client;
    }

    /** Announces that recording is live. Call once when the toggle goes on. */
    public void start() {
        log.info("[menu-recorder] recording to {}", RecorderStore.OBSERVATIONS);
        chat("Menu recording active. Right-click things; entries are logged to ~/.solace/menu-recorder/");
    }

    /** Writes the summary. Call when the toggle goes off, and on plugin shutdown. */
    public void stop() {
        store.writeSummary();
        log.info("[menu-recorder] stopped: {} unique observations ({} duplicates suppressed) -> {}",
                store.uniqueCount(), store.duplicateCount(), RecorderStore.SUMMARY);
        chat("Menu recording stopped. " + store.uniqueCount() + " unique observations written.");
    }

    /** Records one entry. {@code source} is MENU_OPENED or CLICKED. */
    public void observe(MenuEntry entry, String source) {

        if (entry == null) {
            return;
        }
        try {
            record(entry, source);
        } catch (Exception e) {
            // A dev tool must never take the client down with it.
            log.warn("[menu-recorder] failed to record an entry", e);
        }
    }

    private void record(MenuEntry entry, String source) {
        int opcode = entry.getType() == null ? Integer.MIN_VALUE : entry.getType().getId();
        String typeName = entry.getType() == null ? "NULL" : entry.getType().name();
        int identifier = entry.getIdentifier();
        int param0 = entry.getParam0();
        int param1 = entry.getParam1();
        String option = plain(entry.getOption());
        String target = plain(entry.getTarget());

        Ladders.Slot slot = Ladders.slotFor(opcode);
        String targetClass = slot != null ? slot.targetClass : Ladders.describe(opcode);
        int observedOpIndex = slot != null ? slot.opIndex : -1;

        // Resolve the entity behind the entry and pull its live op array.
        String[] actions = null;
        int resolvedId = -1;
        String resolvedName = null;
        Integer sceneX = null;
        Integer sceneY = null;
        String identifierSemantics = null;
        String resolutionNote = null;

        NPC npc = entry.getNpc();
        Player player = entry.getPlayer();
        Widget widget = entry.getWidget();

        if (npc != null) {
            NPCComposition comp = npc.getTransformedComposition();
            if (comp == null) {
                comp = npc.getComposition();
                resolutionNote = "transformedComposition was null; fell back to base composition";
            }
            if (comp != null) {
                actions = comp.getActions();
                resolvedId = comp.getId();
                resolvedName = comp.getName();
            }
            LocalPoint lp = npc.getLocalLocation();
            if (lp != null) {
                sceneX = lp.getSceneX();
                sceneY = lp.getSceneY();
            }
            if (identifier == npc.getIndex()) {
                identifierSemantics = "NPC_INDEX";
            } else if (identifier == npc.getId()) {
                identifierSemantics = "NPC_ID";
            } else {
                identifierSemantics = "UNKNOWN";
            }
        } else if (player != null) {
            resolvedId = player.getId();
            resolvedName = player.getName();
            LocalPoint lp = player.getLocalLocation();
            if (lp != null) {
                sceneX = lp.getSceneX();
                sceneY = lp.getSceneY();
            }
            identifierSemantics = identifier == player.getId() ? "PLAYER_INDEX" : "UNKNOWN";
            // Player op arrays are not exposed by the public API, so the ladder cannot be verified here.
            resolutionNote = "Player actions are not public API; op index unverifiable";
        } else if (widget != null) {
            actions = widget.getActions();
            resolvedId = widget.getId();
            resolvedName = widget.getName();
            identifierSemantics = describeWidgetIdentifier(entry, widget, actions, option);
        } else if ("GAME_OBJECT".equals(targetClass) || opcode == 1002) {
            ObjectComposition comp = client.getObjectDefinition(identifier);
            if (comp != null) {
                // getImpostor() runs a CS2 varbit lookup and throws for objects whose transform config
                // cannot be evaluated here. Losing the transform is far better than losing the whole
                // observation, which is what happened to every varbit-driven object (bank booths) before
                // this was isolated.
                try {
                    ObjectComposition impostor = comp.getImpostor();
                    if (impostor != null) {
                        comp = impostor;
                        resolutionNote = "resolved through impostor";
                    }
                } catch (Exception e) {
                    resolutionNote = "impostor lookup threw (" + e.getClass().getSimpleName()
                            + "); using base composition";
                }
                actions = comp.getActions();
                resolvedId = comp.getId();
                resolvedName = comp.getName();
            }
            identifierSemantics = "OBJECT_ID";
            sceneX = param0;
            sceneY = param1;
        } else if ("GROUND_ITEM".equals(targetClass) || opcode == 1004) {
            ItemComposition comp = client.getItemDefinition(identifier);
            if (comp != null) {
                resolvedId = comp.getId();
                resolvedName = comp.getName();
            }
            identifierSemantics = "ITEM_ID";
            sceneX = param0;
            sceneY = param1;
            // Ground op arrays are not exposed publicly (only getInventoryActions()), so no verification.
            resolutionNote = "ground actions are not public API; op index unverifiable";
        }

        // Verify: does the ladder predict the opcode the client actually emitted?
        int expectedOpIndex = indexOfAction(actions, option);
        String verdict = "UNVERIFIED";
        int predicted = -1;
        if (expectedOpIndex >= 0) {
            if (opcode == Ladders.CC_OP || opcode == Ladders.CC_OP_LOW_PRIORITY) {
                // Widgets carry the op slot in the identifier, not the opcode.
                verdict = identifier == expectedOpIndex + 1 ? "MATCH" : "MISMATCH";
                predicted = expectedOpIndex + 1;
            } else if (slot != null) {
                predicted = Ladders.predict(targetClass, expectedOpIndex);
                verdict = predicted == opcode ? "MATCH" : "MISMATCH";
            }
        }

        String paramSemantics = describeParams(param0, param1, sceneX, sceneY, widget, entry);

        // Where the params carry coordinates they are part of the observation, not noise: collapsing
        // them loses exactly the variation we are trying to measure (e.g. every "Walk here" would
        // dedupe to a single record). Widget params are structural and stay collapsed.
        boolean paramsVary = !"ZERO".equals(paramSemantics)
                && !"P0_CHILD_INDEX__P1_WIDGET_ID".equals(paramSemantics)
                && !"P1_WIDGET_ID".equals(paramSemantics);

        String dedupeKey = source + '|' + opcode + '|' + option + '|' + resolvedId + '|'
                + targetClass + '|' + identifierSemantics + '|' + paramSemantics + '|' + verdict
                + (paramsVary ? "|" + param0 + ',' + param1 : "");
        if (!store.isNew(dedupeKey)) {
            return;
        }

        Json json = new Json()
                .add("source", source)
                .add("tick", client.getTickCount())
                .add("gameCycle", client.getGameCycle())
                // --- observed, exactly as the client built it ---
                .add("opcode", opcode)
                .add("opcodeName", typeName)
                .add("identifier", identifier)
                .add("param0", param0)
                .add("param1", param1)
                .add("itemId", entry.getItemId())
                .add("worldViewId", entry.getWorldViewId())
                .add("isItemOp", entry.isItemOp())
                .add("itemOp", entry.isItemOp() ? entry.getItemOp() : -1)
                .add("deprioritized", entry.isDeprioritized())
                .add("forceLeftClick", entry.isForceLeftClick())
                .add("option", option)
                .add("target", target)
                .add("optionRaw", entry.getOption())
                .add("targetRaw", entry.getTarget())
                // --- resolved ---
                .add("targetClass", targetClass)
                .add("observedOpIndex", observedOpIndex)
                .add("resolvedId", resolvedId)
                .add("resolvedName", resolvedName)
                .addStrings("actions", actions)
                .add("matchedOpIndex", expectedOpIndex)
                .add("identifierSemantics", identifierSemantics)
                .add("paramSemantics", paramSemantics)
                .add("resolutionNote", resolutionNote)
                // --- derived ---
                .add("predicted", predicted)
                .add("verdict", verdict)
                .add("isTargetOp", Ladders.isTargetOp(opcode))
                // --- selection state, needed to reproduce target-on ops ---
                .add("widgetSelected", client.isWidgetSelected())
                .add("selectedWidgetId", selectedWidgetId())
                .add("selectedWidgetItemId", selectedWidgetItemId());

        if (sceneX != null) {
            json.add("entitySceneX", sceneX).add("entitySceneY", sceneY);
        }

        store.record(json.done());
        store.tally(targetClass, expectedOpIndex >= 0 ? expectedOpIndex : observedOpIndex, opcode,
                verdict, paramSemantics, identifierSemantics,
                option + " -> opcode=" + opcode + " id=" + identifier + " p=(" + param0 + "," + param1 + ")");

        log.debug("[menu-recorder] {} {} op={} idx={} verdict={} params={}",
                source, targetClass, opcode, expectedOpIndex, verdict, paramSemantics);

        if (store.uniqueCount() % 25 == 0) {
            chat("Menu Recorder: " + store.uniqueCount() + " unique observations.");
        }
    }

    /** For widgets the op slot lives in the identifier, so that is what gets described. */
    private String describeWidgetIdentifier(MenuEntry entry, Widget widget, String[] actions, String option) {
        int idx = indexOfAction(actions, option);
        if (idx >= 0 && entry.getIdentifier() == idx + 1) {
            return "OP_INDEX_PLUS_1";
        }
        if (entry.getIdentifier() == widget.getIndex()) {
            return "WIDGET_CHILD_INDEX";
        }
        return "UNKNOWN";
    }

    private String describeParams(int param0, int param1, Integer sceneX, Integer sceneY,
                                  Widget widget, MenuEntry entry) {
        if (param0 == 0 && param1 == 0) {
            return "ZERO";
        }
        if (sceneX != null && param0 == sceneX && param1 == sceneY) {
            return "SCENE_COORDS";
        }
        if (widget != null) {
            boolean p1IsWidget = param1 == widget.getId();
            boolean p0IsIndex = param0 == widget.getIndex();
            if (p1IsWidget && p0IsIndex) {
                return "P0_CHILD_INDEX__P1_WIDGET_ID";
            }
            if (p1IsWidget) {
                return "P1_WIDGET_ID";
            }
        }
        if (param0 == -1) {
            return "P0_NEG1__P1_" + param1;
        }
        return "OTHER";
    }

    private int selectedWidgetId() {
        Widget selected = client.getSelectedWidget();
        return selected == null ? -1 : selected.getId();
    }

    private int selectedWidgetItemId() {
        Widget selected = client.getSelectedWidget();
        return selected == null ? -1 : selected.getItemId();
    }

    /** Case-insensitive positional lookup. Nulls in the array are real gaps and must not shift indices. */
    private static int indexOfAction(String[] actions, String option) {
        if (actions == null || option == null || option.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] != null && actions[i].equalsIgnoreCase(option)) {
                return i;
            }
        }
        return -1;
    }

    /** Strips RS colour/formatting tags so option text can be matched against composition actions. */
    private static String plain(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("<[^>]*>", "").trim();
    }

    /**
     * Best-effort chat notice. Catches {@link Throwable} rather than {@code Exception} because
     * {@code addChatMessage} asserts it is on the client thread, and plugins start and stop on the
     * EDT - so under {@code -ea} this throws {@link AssertionError}, which is an {@code Error}.
     *
     * <p>The narrow catch made this a cosmetic message that took the plugin down with it: startUp()
     * failed outright, and shutDown() escaped {@code stopPlugins()} and stranded every plugin ordered
     * after this one, control API included. A courtesy message must never be able to do that.
     */
    private void chat(String message) {
        try {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Menu recorder] " + message, null);
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable e) {
            log.debug("[menu-recorder] chat message failed", e);
        }
    }
}
