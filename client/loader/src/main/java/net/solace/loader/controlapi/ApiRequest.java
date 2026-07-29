package net.solace.loader.controlapi;

import com.google.gson.JsonObject;
import lombok.Getter;

/**
 * The body of {@code POST /api/command}. Deserialized by gson, so the fields are assigned
 * reflectively and there is no constructor.
 */
@Getter
public final class ApiRequest {
    private String requestId;
    private String command;
    private JsonObject params;

    public JsonObject getParams() {
        return params == null ? new JsonObject() : params;
    }
}
