package net.solace.loader.controlapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parameter accessors for command handlers. Every failure is an {@link ApiCommandException} carrying
 * {@code INVALID_ARGUMENT}, so a malformed request is reported as a 400 naming the offending
 * parameter rather than surfacing as a gson {@code ClassCastException} behind a generic 500.
 */
public final class Params {
    private Params() {
    }

    public static JsonElement required(JsonObject params, String name) throws ApiCommandException {
        var value = params.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ApiCommandException("INVALID_ARGUMENT", "Missing parameter: " + name);
        }
        return value;
    }

    public static String requiredString(JsonObject params, String name) throws ApiCommandException {
        try {
            var value = required(params, name).getAsString();
            if (value.trim().isEmpty()) {
                throw new ApiCommandException("INVALID_ARGUMENT", "Parameter is empty: " + name);
            }
            return value;
        } catch (UnsupportedOperationException | ClassCastException e) {
            throw new ApiCommandException("INVALID_ARGUMENT", "Parameter must be a string: " + name);
        }
    }

    public static boolean requiredBoolean(JsonObject params, String name) throws ApiCommandException {
        try {
            return required(params, name).getAsBoolean();
        } catch (UnsupportedOperationException | ClassCastException e) {
            throw new ApiCommandException("INVALID_ARGUMENT", "Parameter must be a boolean: " + name);
        }
    }

    public static int requiredInt(JsonObject params, String name) throws ApiCommandException {
        try {
            return required(params, name).getAsInt();
        } catch (UnsupportedOperationException | ClassCastException | NumberFormatException e) {
            throw new ApiCommandException("INVALID_ARGUMENT", "Parameter must be an integer: " + name);
        }
    }

    public static String optionalString(JsonObject params, String name, String fallback) {
        return isPrimitive(params, name) ? params.get(name).getAsString() : fallback;
    }

    public static boolean optionalBoolean(JsonObject params, String name, boolean fallback) {
        return isPrimitive(params, name) ? params.get(name).getAsBoolean() : fallback;
    }

    public static int optionalInt(JsonObject params, String name, int fallback) {
        if (!isPrimitive(params, name)) {
            return fallback;
        }
        try {
            return params.get(name).getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static List<String> optionalStringList(JsonObject params, String name) {
        var values = new ArrayList<String>();
        if (params == null || !params.has(name) || !params.get(name).isJsonArray()) {
            return values;
        }
        for (var element : params.getAsJsonArray(name)) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    /**
     * Gate for anything that mutates client state. Requires an explicit {@code "confirm": true} so a
     * mistyped command name can never write to the game.
     */
    public static void requireConfirmation(JsonObject params) throws ApiCommandException {
        if (!optionalBoolean(params, "confirm", false)) {
            throw new ApiCommandException(
                    "INVALID_ARGUMENT", "This command mutates client state; pass \"confirm\": true");
        }
    }

    private static boolean isPrimitive(JsonObject params, String name) {
        return params != null && params.has(name) && params.get(name).isJsonPrimitive();
    }
}
