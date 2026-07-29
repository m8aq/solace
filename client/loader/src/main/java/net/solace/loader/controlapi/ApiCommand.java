package net.solace.loader.controlapi;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface ApiCommand {
    Object execute(JsonObject params) throws Exception;
}
