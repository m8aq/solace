package net.solace.loader.plugins.menurecorder;

import java.util.Map;

/**
 * Minimal JSON emitter. Hand-rolled deliberately: {@code plugins/bundled} has no JSON dependency and
 * adding one for a dev tool is not worth the coupling.
 */
final class Json {
    private final StringBuilder sb = new StringBuilder(512);
    private boolean first = true;

    Json() {
        sb.append('{');
    }

    Json add(String key, String value) {
        if (value == null) {
            return this;
        }
        sep();
        key(key).append('"').append(escape(value)).append('"');
        return this;
    }

    Json add(String key, int value) {
        sep();
        key(key).append(value);
        return this;
    }

    Json add(String key, boolean value) {
        sep();
        key(key).append(value);
        return this;
    }

    /** Adds a pre-rendered raw JSON value (object, array, number). */
    Json raw(String key, String rawJson) {
        if (rawJson == null) {
            return this;
        }
        sep();
        key(key).append(rawJson);
        return this;
    }

    Json addStrings(String key, String[] values) {
        if (values == null) {
            return this;
        }
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            if (values[i] == null) {
                arr.append("null");
            } else {
                arr.append('"').append(escape(values[i])).append('"');
            }
        }
        return raw(key, arr.append(']').toString());
    }

    String done() {
        return sb.append('}').toString();
    }

    private void sep() {
        if (!first) {
            sb.append(',');
        }
        first = false;
    }

    private StringBuilder key(String key) {
        return sb.append('"').append(escape(key)).append("\":");
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    static String objectOfCounts(Map<String, Integer> counts) {
        StringBuilder out = new StringBuilder("{");
        boolean f = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!f) {
                out.append(',');
            }
            f = false;
            out.append('"').append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        return out.append('}').toString();
    }
}
