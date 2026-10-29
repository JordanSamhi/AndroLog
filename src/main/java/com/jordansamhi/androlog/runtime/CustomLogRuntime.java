package com.jordansamhi.androlog.runtime;

/** Typed local-value logging using only APIs available on old Android versions. */
public final class CustomLogRuntime {
    private static final int MAX_CHARS = 3000;
    private static final int MAX_BYTES = 3000;
    private CustomLogRuntime() { }

    public static StringBuilder create() { return new StringBuilder(); }
    public static void text(StringBuilder output, String value) { append(output, value); }
    public static void value(StringBuilder output, byte value) { append(output, Integer.toString(value)); }
    public static void value(StringBuilder output, short value) { append(output, Integer.toString(value)); }
    public static void value(StringBuilder output, int value) { append(output, Integer.toString(value)); }
    public static void value(StringBuilder output, long value) { append(output, Long.toString(value)); }
    public static void value(StringBuilder output, float value) { append(output, Float.toString(value)); }
    public static void value(StringBuilder output, double value) { append(output, Double.toString(value)); }
    public static void value(StringBuilder output, boolean value) { append(output, Boolean.toString(value)); }
    public static void value(StringBuilder output, String value) {
        if (value == null) { append(output, "null"); return; }
        for (int i = 0; i < value.length() && output.length() <= MAX_CHARS; i++) {
            char next = value.charAt(i);
            if (next == '\\') append(output, "\\\\");
            else if (next == '\n') append(output, "\\n");
            else if (next == '\r') append(output, "\\r");
            else if (next == '\0') append(output, "\\0");
            else output.append(next);
        }
    }
    private static void append(StringBuilder output, String value) {
        // Retain one extra character as a truncation sentinel, avoiding huge temporary strings.
        int remaining = MAX_CHARS + 1 - output.length();
        if (remaining > 0) output.append(value, 0, Math.min(value.length(), remaining));
    }

    public static void write(String tag, String id, String template, StringBuilder output) {
        String value = output.toString();
        int bytes = 0, end = 0;
        while (end < value.length()) {
            int code = value.codePointAt(end);
            int count = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
            if (bytes + count > MAX_BYTES - 3) break;
            bytes += count; end += Character.charCount(code);
        }
        if (end < value.length()) value = value.substring(0, end) + "...";
        emit(tag, "CUSTOM_VALUE=" + id + "|" + value);
        emit(tag, "CUSTOM_POINT=" + id);
    }

    /** Replaced with android.util.Log.i during APK instrumentation. */
    public static void emit(String tag, String message) { System.out.println(tag + ": " + message); }
}
