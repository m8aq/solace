package net.solace.loader.controlapi;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A flat name-to-handler map. easy-rl carries a second path-scoped registry alongside this one; that
 * exists only to namespace its RMAPI gameplay surface, which this port does not include.
 */
public final class ApiCommandRegistry {
    private final Map<String, ApiCommand> commands = new LinkedHashMap<>();

    public void register(String name, ApiCommand command) {
        if (commands.putIfAbsent(name, command) != null) {
            throw new IllegalArgumentException("Duplicate API command: " + name);
        }
    }

    public Object execute(String name, JsonObject params) throws Exception {
        var command = commands.get(name);
        if (command == null) {
            throw new ApiCommandException("COMMAND_NOT_FOUND", "Unknown command: " + name);
        }
        return command.execute(params == null ? new JsonObject() : params);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    public void clear() {
        commands.clear();
    }
}
