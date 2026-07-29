package net.solace.loader.controlapi;

public final class ApiResponse {
    private final String requestId;
    private final boolean ok;
    private final String command;
    private final Object result;
    private final ApiError error;

    /**
     * When the command's client-thread read actually happened, or null for commands that never
     * touched the client thread. Lets a caller tell whether a batch of reads straddled a game tick.
     */
    private final Long capturedAt;

    private ApiResponse(String requestId, boolean ok, String command, Object result, ApiError error,
                        Long capturedAt) {
        this.requestId = requestId;
        this.ok = ok;
        this.command = command;
        this.result = result;
        this.error = error;
        this.capturedAt = capturedAt;
    }

    public static ApiResponse success(String requestId, String command, Object result) {
        return success(requestId, command, result, null);
    }

    public static ApiResponse success(String requestId, String command, Object result, Long capturedAt) {
        return new ApiResponse(requestId, true, command, result, null, capturedAt);
    }

    public static ApiResponse failure(String requestId, String command, String code, String message) {
        return new ApiResponse(requestId, false, command, null, new ApiError(code, message), null);
    }
}
