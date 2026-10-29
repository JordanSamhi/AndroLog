package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches runtime observations against the exact entities in the APK baseline. */
public class LogParser {
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    private static final Pattern MESSAGE = Pattern.compile(
            "^(STATEMENT|METHOD|CLASS|ACTIVITY|SERVICE|BROADCASTRECEIVER|CONTENTPROVIDER)=(.*)$");

    static {
        TYPES.put("STATEMENT", "statements");
        TYPES.put("METHOD", "methods");
        TYPES.put("CLASS", "classes");
        TYPES.put("ACTIVITY", "activities");
        TYPES.put("SERVICE", "services");
        TYPES.put("BROADCASTRECEIVER", "broadcast-receivers");
        TYPES.put("CONTENTPROVIDER", "content-providers");
    }

    private final Pattern taggedLine;
    private final SummaryBuilder summaryBuilder;
    private final Map<String, Set<String>> baseline = new HashMap<>();
    private final SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

    public LogParser(String logIdentifier, SummaryBuilder summaryBuilder) {
        this.summaryBuilder = summaryBuilder;
        // Standard threadtime/time/tag-only and brief logcat formats. Match the
        // complete tag, so a tag substring inside another message cannot add edges.
        String timestamp = "(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?";
        String tag = Pattern.quote(logIdentifier);
        this.taggedLine = Pattern.compile("^(?:(?:" + timestamp + "\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?"
                + tag + "\\s*:\\s*|(?:" + timestamp + "\\s+)?[VDIWEFA]/" + tag
                + "(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)(.*)$");
        for (String type : TYPES.values()) {
            baseline.put(type, new HashSet<>());
        }
        for (String entity : summaryBuilder.getVisitedComponents()) {
            for (Map.Entry<String, String> entry : TYPES.entrySet()) {
                String prefix = entry.getValue();
                if ("STATEMENT".equals(entry.getKey())) {
                    prefix += "STATEMENT=";
                }
                if (entity.startsWith(prefix)) {
                    baseline.get(entry.getValue()).add(entity.substring(prefix.length()));
                    break;
                }
            }
        }
    }

    public void parseLogs(String filePath) {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher tagged = taggedLine.matcher(line);
                if (!tagged.matches()) continue;
                String message = tagged.group(1);
                if (message.startsWith("CUSTOM_POINT=")) {
                    String id = message.substring("CUSTOM_POINT=".length());
                    if (id.matches("[a-f0-9]{32}")) summaryLogBuilder.recordCustomMarker(id);
                    continue;
                }
                if (message.startsWith("CUSTOM=")) {
                    summaryLogBuilder.recordCustomMessage(message.substring("CUSTOM=".length()));
                    continue;
                }
                if (message.startsWith("MC2|") || message.startsWith("CALL=")) {
                    summaryLogBuilder.getCallGraph().accept(message,
                            summaryBuilder.getCallSites(), summaryBuilder.getGraphMethods());
                    continue;
                }
                Matcher matcher = MESSAGE.matcher(message);
                if (matcher.find()) {
                    String type = TYPES.get(matcher.group(1));
                    String payload = matcher.group(2);
                    // AndroSpecter 2 adds a callback subsignature to component messages.
                    // Coverage remains per component class, including for older class-only logs.
                    if (isAndroidComponent(type)) {
                        int callback = payload.indexOf('|');
                        if (callback >= 0) payload = payload.substring(0, callback);
                    }
                    if (baseline.get(type).contains(payload)) {
                        summaryLogBuilder.record(type, payload, line);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read runtime logs: " + filePath, e);
        }
    }

    private static boolean isAndroidComponent(String type) {
        return "activities".equals(type) || "services".equals(type)
                || "broadcast-receivers".equals(type) || "content-providers".equals(type);
    }
}
