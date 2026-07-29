package net.solace.loader.plugins.menurecorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The opcode ladders, extracted from {@code net.runelite.api.MenuAction} rather than guessed.
 *
 * <p>Each entity class assigns one opcode per op slot, so a menu entry's opcode encodes <em>which
 * op</em> and its identifier encodes <em>which target</em>. Widgets invert that: the opcode is fixed
 * ({@code CC_OP}) and the op slot moves into the identifier. Both forms are represented here so the
 * recorder can report which one this client actually emits.
 *
 * <p>Note {@code GAME_OBJECT} breaks the arithmetic at the fifth slot (1001, not 7), which is why
 * this is a table and not a {@code base + i} expression.
 */
final class Ladders {
    static final int CC_OP = 57;
    static final int CC_OP_LOW_PRIORITY = 1007;
    static final int WALK = 23;

    /**
     * The selection op. Left-clicking "Use" on an item or "Cast" on a spell emits this, and the client
     * sets its own selection state as a result — which is what makes the {@code setSelectedSpell*}
     * writes unnecessary: invoke this, then invoke the target op.
     */
    static final int WIDGET_TARGET = 25;

    /** Opcode ladders per entity class, index = op slot. */
    static final Map<String, int[]> LADDERS;

    /** Opcodes that mean "use selected item/widget on target", per entity class. */
    static final Map<String, int[]> TARGET_OPCODES;

    /** Examine opcodes, for classification only. */
    private static final Map<Integer, String> EXAMINE;

    private static final Map<Integer, Slot> BY_OPCODE;

    static final class Slot {
        final String targetClass;
        final int opIndex;

        Slot(String targetClass, int opIndex) {
            this.targetClass = targetClass;
            this.opIndex = opIndex;
        }
    }

    static {
        Map<String, int[]> ladders = new LinkedHashMap<>();
        ladders.put("NPC", new int[]{9, 10, 11, 12, 13});
        ladders.put("GAME_OBJECT", new int[]{3, 4, 5, 6, 1001});
        ladders.put("GROUND_ITEM", new int[]{18, 19, 20, 21, 22});
        ladders.put("PLAYER", new int[]{44, 45, 46, 47, 48, 49, 50, 51});
        ladders.put("WORLD_ENTITY", new int[]{63, 64, 65, 66, 67});
        // Legacy forms. Modern clients route both through CC_OP; recorded so we can prove that.
        ladders.put("WIDGET_LEGACY", new int[]{39, 40, 41, 42, 43});
        ladders.put("INV_ITEM_LEGACY", new int[]{33, 34, 35, 36, 37});
        LADDERS = Collections.unmodifiableMap(ladders);

        Map<String, int[]> targets = new LinkedHashMap<>();
        // { ITEM_USE_ON_x (legacy item selection), WIDGET_TARGET_ON_x (widget/spell selection) }
        targets.put("GAME_OBJECT", new int[]{1, 2});
        targets.put("NPC", new int[]{7, 8});
        targets.put("PLAYER", new int[]{14, 15});
        targets.put("GROUND_ITEM", new int[]{16, 17});
        targets.put("ITEM", new int[]{31, 32});
        targets.put("WIDGET", new int[]{-1, 58});
        TARGET_OPCODES = Collections.unmodifiableMap(targets);

        Map<Integer, String> examine = new HashMap<>();
        examine.put(1002, "GAME_OBJECT");
        examine.put(1003, "NPC");
        examine.put(1004, "GROUND_ITEM");
        examine.put(1005, "ITEM");
        examine.put(1013, "WORLD_ENTITY");
        EXAMINE = Collections.unmodifiableMap(examine);

        Map<Integer, Slot> byOpcode = new HashMap<>();
        for (Map.Entry<String, int[]> e : LADDERS.entrySet()) {
            int[] ids = e.getValue();
            for (int i = 0; i < ids.length; i++) {
                byOpcode.put(ids[i], new Slot(e.getKey(), i));
            }
        }
        BY_OPCODE = Collections.unmodifiableMap(byOpcode);
    }

    private Ladders() {
    }

    /**
     * Classifies a raw opcode into (entity class, op slot), or {@code null} if the opcode is not a
     * per-slot entity op. Callers should fall back to {@link #describe(int)}.
     */
    static Slot slotFor(int opcode) {
        return BY_OPCODE.get(opcode);
    }

    /** The opcode {@code targetClass} should use for {@code opIndex}, or -1 if out of range. */
    static int predict(String targetClass, int opIndex) {
        int[] ids = LADDERS.get(targetClass);
        if (ids == null || opIndex < 0 || opIndex >= ids.length) {
            return -1;
        }
        return ids[opIndex];
    }

    /** A coarse human label for opcodes that are not per-slot entity ops. */
    static String describe(int opcode) {
        if (opcode == CC_OP) {
            return "CC_OP";
        }
        if (opcode == CC_OP_LOW_PRIORITY) {
            return "CC_OP_LOW_PRIORITY";
        }
        if (opcode == WALK) {
            return "WALK";
        }
        if (opcode == 24) {
            return "WIDGET_TYPE_1";
        }
        if (opcode == WIDGET_TARGET) {
            return "WIDGET_TARGET";
        }
        if (opcode == 26) {
            return "WIDGET_CLOSE";
        }
        if (opcode == 28) {
            return "WIDGET_TYPE_4";
        }
        if (opcode == 29) {
            return "WIDGET_TYPE_5";
        }
        if (opcode == 30) {
            return "WIDGET_CONTINUE";
        }
        if (opcode == 38) {
            return "ITEM_USE";
        }
        if (opcode == 1006) {
            return "CANCEL";
        }
        String examine = EXAMINE.get(opcode);
        if (examine != null) {
            return "EXAMINE_" + examine;
        }
        for (Map.Entry<String, int[]> e : TARGET_OPCODES.entrySet()) {
            int[] ids = e.getValue();
            if (opcode == ids[0]) {
                return "ITEM_USE_ON_" + e.getKey();
            }
            if (opcode == ids[1]) {
                return "WIDGET_TARGET_ON_" + e.getKey();
            }
        }
        if (opcode >= 1500) {
            return "RUNELITE_SYNTHETIC";
        }
        return "UNCLASSIFIED";
    }

    /** True when the opcode is a target-on op, which needs client selection state to be set. */
    static boolean isTargetOp(int opcode) {
        for (int[] ids : TARGET_OPCODES.values()) {
            if (opcode == ids[0] || opcode == ids[1]) {
                return true;
            }
        }
        return false;
    }
}
