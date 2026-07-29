package net.solace.loader.plugins.menurecorder;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deduplicates observations, appends them as JSONL, and maintains a rolled-up summary.
 *
 * <p>Two files, both at stable paths so they can be read while the client is still running:
 * <ul>
 *   <li>{@code ~/.solace/menu-recorder/observations.jsonl} — one JSON object per unique observation
 *   <li>{@code ~/.solace/menu-recorder/summary.json} — the derived ladder table
 * </ul>
 */
@Slf4j
final class RecorderStore {
    static final Path DIR = Paths.get(System.getProperty("user.home"), ".solace", "menu-recorder");
    static final Path OBSERVATIONS = DIR.resolve("observations.jsonl");
    static final Path SUMMARY = DIR.resolve("summary.json");

    /** Rewrite the summary every N new unique observations so it can be read mid-session. */
    private static final int SUMMARY_EVERY = 10;

    private final Set<String> seen = new HashSet<>();
    private final Map<String, ClassStats> stats = new LinkedHashMap<>();
    private int unique;
    private int duplicates;

    private static final class ClassStats {
        final Map<Integer, Set<Integer>> opIndexToOpcodes = new TreeMap<>();
        final Map<String, Integer> paramSemantics = new LinkedHashMap<>();
        final Map<String, Integer> identifierSemantics = new LinkedHashMap<>();
        final List<String> examples = new ArrayList<>();
        int match;
        int mismatch;
        int unverified;
    }

    boolean isNew(String dedupeKey) {
        if (seen.add(dedupeKey)) {
            return true;
        }
        duplicates++;
        return false;
    }

    void record(String jsonLine) {
        unique++;
        try {
            Files.createDirectories(DIR);
            Files.write(OBSERVATIONS, (jsonLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("[menu-recorder] could not append observation", e);
        }
        if (unique % SUMMARY_EVERY == 0) {
            writeSummary();
        }
    }

    void tally(String targetClass, int opIndex, int opcode, String verdict,
               String paramSemantics, String identifierSemantics, String example) {
        ClassStats cs = stats.computeIfAbsent(targetClass, k -> new ClassStats());
        if (opIndex >= 0) {
            cs.opIndexToOpcodes.computeIfAbsent(opIndex, k -> new TreeSet<>()).add(opcode);
        }
        if (paramSemantics != null) {
            cs.paramSemantics.merge(paramSemantics, 1, Integer::sum);
        }
        if (identifierSemantics != null) {
            cs.identifierSemantics.merge(identifierSemantics, 1, Integer::sum);
        }
        if ("MATCH".equals(verdict)) {
            cs.match++;
        } else if ("MISMATCH".equals(verdict)) {
            cs.mismatch++;
        } else {
            cs.unverified++;
        }
        if (example != null && cs.examples.size() < 3 && !cs.examples.contains(example)) {
            cs.examples.add(example);
        }
    }

    int uniqueCount() {
        return unique;
    }

    int duplicateCount() {
        return duplicates;
    }

    void writeSummary() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"uniqueObservations\": ").append(unique)
                .append(",\n  \"duplicatesSuppressed\": ").append(duplicates)
                .append(",\n  \"classes\": {\n");

        boolean firstClass = true;
        for (Map.Entry<String, ClassStats> e : stats.entrySet()) {
            if (!firstClass) {
                out.append(",\n");
            }
            firstClass = false;
            ClassStats cs = e.getValue();
            out.append("    \"").append(Json.escape(e.getKey())).append("\": {\n");
            out.append("      \"verdicts\": {\"match\": ").append(cs.match)
                    .append(", \"mismatch\": ").append(cs.mismatch)
                    .append(", \"unverified\": ").append(cs.unverified).append("},\n");

            out.append("      \"opIndexToOpcode\": {");
            boolean firstSlot = true;
            for (Map.Entry<Integer, Set<Integer>> slot : cs.opIndexToOpcodes.entrySet()) {
                if (!firstSlot) {
                    out.append(", ");
                }
                firstSlot = false;
                out.append('"').append(slot.getKey()).append("\": ").append(slot.getValue());
            }
            out.append("},\n");

            out.append("      \"identifierSemantics\": ")
                    .append(Json.objectOfCounts(cs.identifierSemantics)).append(",\n");
            out.append("      \"paramSemantics\": ")
                    .append(Json.objectOfCounts(cs.paramSemantics)).append(",\n");

            out.append("      \"examples\": [");
            for (int i = 0; i < cs.examples.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append('"').append(Json.escape(cs.examples.get(i))).append('"');
            }
            out.append("]\n    }");
        }
        out.append("\n  }\n}\n");

        try {
            Files.createDirectories(DIR);
            Files.write(SUMMARY, out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("[menu-recorder] could not write summary", e);
        }
    }
}
