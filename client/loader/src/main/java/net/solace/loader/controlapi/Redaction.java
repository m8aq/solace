package net.solace.loader.controlapi;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single place credentials get scrubbed before leaving the process.
 *
 * <p>Merges two heuristics that previously lived apart: easy-rl's {@code PluginLogService} regex,
 * which catches {@code key=value} pairs embedded in free-form log text, and the key-name check from
 * Solace's own diagnostics, which catch a value whose <em>name</em> marks it sensitive even
 * when the value itself looks innocuous.
 *
 * <p>Neither is sufficient alone: the regex cannot tell that a bare string returned from a hook
 * called {@code username} is a credential, and the name check cannot see a password embedded
 * mid-sentence in a log line.
 */
public final class Redaction {
    private static final int MAX_LENGTH = 16_384;

    /**
     * Matches {@code password: hunter2}, {@code Authorization: Bearer abc.def}, and friends.
     *
     * <p>The optional scheme group is not cosmetic. Without it {@code Authorization: Bearer abc.def}
     * matches only up to the first space, so the literal word "Bearer" gets redacted and the actual
     * token is left in the clear - which is worse than not redacting at all, because the output looks
     * sanitised.
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|authorization|bearer|access[_-]?token|refresh[_-]?token"
                    + "|session[_-]?id|character[_-]?id|otp|api[_-]?key)\\s*[:=]\\s*"
                    + "(?:(?:bearer|basic|token|digest)\\s+)?([^\\s,}\"]+)");

    /**
     * Name fragments that make a value sensitive regardless of its content. A username is half a
     * credential pair and is usually an email address tied to a real account.
     */
    private static final String[] SENSITIVE_NAMES = {
            "password", "passwd", "otp", "session", "username", "email", "characterid",
            "displayname", "token", "secret", "credential", "auth", "pin",
    };

    private Redaction() {
    }

    /** Scrubs assignments inside free text, and truncates. For log messages and stack traces. */
    public static String redactText(String value) {
        if (value == null) {
            return null;
        }
        return SECRET_ASSIGNMENT.matcher(limit(value)).replaceAll("$1=[REDACTED]");
    }

    /**
     * Scrubs a value whose name marks it sensitive, reporting length only so a caller can still tell
     * "set" from "not set" - which is most of the diagnostic value without exposing the secret.
     */
    public static Object redactNamed(String name, Object value) {
        if (!(value instanceof String) || !isSensitiveName(name)) {
            return value;
        }
        var text = (String) value;
        return text.isEmpty() ? "<empty>" : "<redacted length=" + text.length() + ">";
    }

    public static boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        var candidate = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (var term : SENSITIVE_NAMES) {
            if (candidate.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String limit(String value) {
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH) + "…";
    }
}
