package com.jordansamhi.androlog.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A log-only view: counts observations without claiming an APK coverage baseline. */
final class RuntimeLog {
    static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("CLASS", "Classes"); TYPES.put("METHOD", "Methods"); TYPES.put("STATEMENT", "Statements");
        TYPES.put("ACTIVITY", "Activities"); TYPES.put("SERVICE", "Services");
        TYPES.put("BROADCASTRECEIVER", "Broadcast receivers"); TYPES.put("CONTENTPROVIDER", "Content providers");
        TYPES.put("CUSTOM", "Custom messages"); TYPES.put("CALLS", "Call records");
    }
    final Map<String, Map<String, Long>> entries = new LinkedHashMap<>();
    final Map<String, Long> minutes = new TreeMap<>();
    long lines, tagged, recognized;
    RuntimeLog() { for (String type : TYPES.keySet()) entries.put(type, new LinkedHashMap<>()); }

    static RuntimeLog read(Path path, String tag) throws IOException {
        if (tag == null || !tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IOException("Enter a valid logcat tag, such as ANDROLOG.");
        String timestamp = "(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?";
        Pattern taggedLine = Pattern.compile("^(?:(?:" + timestamp + "\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?"
                + Pattern.quote(tag) + "\\s*:\\s*|(?:" + timestamp + "\\s+)?[VDIWEFA]/" + Pattern.quote(tag)
                + "(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)(.*)$");
        Pattern minute = Pattern.compile("^((?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}):\\d{2}");
        RuntimeLog result = new RuntimeLog();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Log reading cancelled.");
                result.lines++;
                Matcher match = taggedLine.matcher(line); if (!match.matches()) continue;
                result.tagged++; String message = match.group(1), type, value;
                if (message.startsWith("MC2|") || message.startsWith("CALL=")) { type = "CALLS"; value = message; }
                else {
                    int separator = message.indexOf('='); if (separator <= 0) continue;
                    type = message.substring(0, separator); value = message.substring(separator + 1);
                    if (!TYPES.containsKey(type) || "CALLS".equals(type) || value.isEmpty()) continue;
                    if (Arrays.asList("ACTIVITY", "SERVICE", "BROADCASTRECEIVER", "CONTENTPROVIDER").contains(type)) {
                        int callback = value.indexOf('|'); if (callback >= 0) value = value.substring(0, callback);
                    }
                }
                result.recognized++; result.entries.get(type).merge(value, 1L, Long::sum);
                Matcher time = minute.matcher(line); if (time.find()) result.minutes.merge(time.group(1), 1L, Long::sum);
            }
        }
        return result;
    }
}
