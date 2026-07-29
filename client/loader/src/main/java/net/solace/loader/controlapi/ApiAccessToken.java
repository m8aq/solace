package net.solace.loader.controlapi;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.solace.loader.commons.Directories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * The on-disk file a client uses to discover the running API, and an optional shared secret.
 *
 * <p><b>Tokenless by default.</b> The browser threat - a page the operator visits POSTing a mutating
 * command to 127.0.0.1 - is already closed by two cheaper checks in {@link ApiServer}: no
 * {@code Access-Control-Allow-*} header is ever emitted, so a {@code fetch} carrying
 * {@code Content-Type: application/json} fails its preflight, and a form POST that avoids the
 * preflight cannot set that content type and is refused with a 415. Any {@code Origin} header at all
 * is rejected outright. A token adds protection only against other <em>local</em> processes, which
 * could equally read this file - so it is opt-in via {@code -Dsolace.controlapi.token=true} rather
 * than a default tax on every request.
 *
 * <p>The file is per-PID because Solace is a multi-client tool and a fixed port collides on the
 * second client; the port recorded here is the one actually bound, which matters when the configured
 * port is 0.
 */
@Slf4j
public final class ApiAccessToken {
    private static final String TOKEN_PROPERTY = "solace.controlapi.token";
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Null when running tokenless, which is the default. */
    @Getter
    private final String token;
    private final byte[] tokenBytes;
    private Path file;

    public ApiAccessToken() {
        this(Boolean.getBoolean(TOKEN_PROPERTY));
    }

    public ApiAccessToken(boolean required) {
        if (!required) {
            this.token = null;
            this.tokenBytes = null;
            return;
        }
        var raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isRequired() {
        return tokenBytes != null;
    }

    /**
     * Constant-time comparison. {@link MessageDigest#isEqual} is the JDK's timing-safe array compare;
     * {@code String.equals} short-circuits on the first differing byte.
     */
    public boolean matches(String candidate) {
        if (!isRequired()) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(tokenBytes, candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes the discovery file. Failure is logged, not fatal - the API still works, you just have
     *  to read the token from the log. */
    public void publish(Gson gson, int port) {
        try {
            var dir = Directories.SOLACE_DIR.resolve("controlapi");
            Files.createDirectories(dir);

            var pid = ProcessHandle.current().pid();
            var target = dir.resolve(pid + ".json");

            var body = new LinkedHashMap<String, Object>();
            body.put("pid", pid);
            body.put("port", port);
            body.put("token", token);
            body.put("startedAt", Instant.now().toString());

            Files.write(target, gson.toJson(body).getBytes(StandardCharsets.UTF_8));
            restrictPermissions(target);

            file = target;
            log.info("[control-api] listening on 127.0.0.1:{} ({}), endpoint file {}",
                    port, isRequired() ? "token required" : "tokenless", target);
        } catch (IOException e) {
            log.warn("[control-api] could not write the token file; the token is only in this log", e);
        }
    }

    public void unpublish() {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[control-api] could not delete {}", file, e);
        } finally {
            file = null;
        }
    }

    private static void restrictPermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem. The token is still secret, just not mode-guarded.
            log.debug("[control-api] could not restrict permissions on {}", target, e);
        }
    }
}
