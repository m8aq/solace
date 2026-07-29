package net.solace.loader.controlapi;

import lombok.Getter;

/**
 * A command failure with a machine-readable code. Anything else thrown out of a command is reported
 * as {@code INTERNAL_ERROR} with a generic message, so throw this whenever the caller should be able
 * to tell what went wrong.
 */
@Getter
public final class ApiCommandException extends Exception {
    private final String code;

    public ApiCommandException(String code, String message) {
        super(message);
        this.code = code;
    }
}
