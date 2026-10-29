package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class provides functionality for building a summary log of various components
 * such as methods, classes, statements, activities, services, broadcast receivers,
 * and content providers. It uses a singleton pattern to ensure a single instance
 * is used throughout the application. The class allows tracking and incrementing the
 * count of each component type and provides a method to print the summary in a
 * JSON format.
 */
public class SummaryLogBuilder {
    private static SummaryLogBuilder instance;
    private final Map<String, Integer> summary = new HashMap<>();
    private final Set<String> visitedComponents = new HashSet<>();
    private final Map<String, Map<String, Integer>> perMinuteSummaries = new TreeMap<>(); // TreeMap keeps minutes ordered
    private static final Pattern MINUTE_PREFIX = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2})(?=[:\\s]|$)");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm", Locale.US).withResolverStyle(ResolverStyle.STRICT);
    private int unattributedCount;
    private final CallGraph callGraph = new CallGraph();
    private final Map<String, Integer> customMessages = new LinkedHashMap<>();
    private final Map<String, Integer> customMarkers = new LinkedHashMap<>();
    public Map<String, Integer> getCustomMessages() { return Collections.unmodifiableMap(customMessages); }
    public Map<String, Integer> getCustomMarkers() { return Collections.unmodifiableMap(customMarkers); }
    public void recordCustomMessage(String message) { customMessages.merge(message, 1, Integer::sum); }
    public void recordCustomMarker(String id) { customMarkers.merge(id, 1, Integer::sum); }

    public CallGraph getCallGraph() { return callGraph; }

    /**
     * Private constructor to prevent instantiation from outside the class.
     */
    private SummaryLogBuilder() {
    }

    public static SummaryLogBuilder v() {
        if (instance == null) {
            instance = new SummaryLogBuilder();
        }
        return instance;
    }

    private void increment(String key, Map<String, Integer> map) {
        map.merge(key, 1, Integer::sum);
    }

    /**
     * Increments the count of logged methods.
     *
     * @param method The method to be logged.
     */
    public void incrementMethod(String method) {
        incrementComponent("methods", method);
    }

    /**
     * Increments the count of logged classes.
     *
     * @param clazz The class to be logged.
     */
    public void incrementClass(String clazz) {
        incrementComponent("classes", clazz);
    }

    /**
     * Increments the count of logged statements.
     *
     * @param stmt The statement to be logged.
     */
    public void incrementStatement(String stmt) {
        incrementComponent("statements", stmt);
    }

    /**
     * Increments the count of logged activities.
     *
     * @param activity The activity to be logged.
     */
    public void incrementActivity(String activity) {
        incrementComponent("activities", activity);
    }

    /**
     * Increments the count of logged services.
     *
     * @param service The service to be logged.
     */
    public void incrementService(String service) {
        incrementComponent("services", service);
    }

    /**
     * Increments the count of logged broadcast receivers.
     *
     * @param br The broadcast receiver to be logged.
     */
    public void incrementBroadcastReceiver(String br) {
        incrementComponent("broadcast-receivers", br);
    }

    /**
     * Increments the count of logged content providers.
     *
     * @param cp The content provider to be logged.
     */
    public void incrementContentProvider(String cp) {
        incrementComponent("content-providers", cp);
    }

    /**
     * Increments the count for a specific component type and component name.
     * Ensures that each unique component is only counted once.
     *
     * @param type      The type of component (e.g., "methods", "classes").
     * @param component The name of the component.
     */
    private void incrementComponent(String type, String component) {
        record(type, extractContent(type, component), component);
    }

    /**
     * Records a validated entity payload once. A missing or invalid timestamp does
     * not prevent aggregate coverage, but the observation has no minute bucket.
     */
    public void record(String type, String payload, String originalLine) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        String minuteKey = extractMinuteKey(originalLine);
        Map<String, Integer> minuteSummary = minuteKey == null ? null
                : perMinuteSummaries.computeIfAbsent(minuteKey, key -> new HashMap<>());
        if (!visitedComponents.add(type + payload)) {
            return;
        }
        increment(type, summary);
        if (minuteSummary == null) {
            unattributedCount++;
            return;
        }
        increment(type, minuteSummary);
    }

    private String extractContent(String type, String line) {
        if (line == null) {
            return null;
        }
        String label;
        switch (type) {
            case "methods": label = "METHOD="; break;
            case "classes": label = "CLASS="; break;
            case "statements": label = "STATEMENT="; break;
            case "activities": label = "ACTIVITY="; break;
            case "services": label = "SERVICE="; break;
            case "broadcast-receivers": label = "BROADCASTRECEIVER="; break;
            case "content-providers": label = "CONTENTPROVIDER="; break;
            default: return null;
        }
        int start = line.indexOf(label);
        return start < 0 ? null : line.substring(start + label.length());
    }

    /**
     * Parse log line to get minute string like "07-03 11:05"
     */
    private String extractMinuteKey(String logLine) {
        if (logLine == null) {
            return null;
        }
        Matcher matcher = MINUTE_PREFIX.matcher(logLine);
        if (!matcher.find()) {
            return null;
        }
        String minute = matcher.group(1);
        try {
            // Logcat omits the year; a leap year keeps February 29 valid.
            LocalDateTime.parse("2000-" + minute, MINUTE_FORMAT);
            return minute;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Prints the summary of all logged components in a JSON format to the console.
     */
    public void printSummary() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(summary));
    }

    /**
     * Retrieves the current summary map of component counts.
     *
     * @return A map representing the counts of different logged components.
     */
    public Map<String, Integer> getSummary() {
        return summary;
    }

    /**
     * Returns the observed entity identifiers without exposing the mutable set.
     */
    public Set<String> getVisitedComponents() {
        return Collections.unmodifiableSet(visitedComponents);
    }

    public int getUnattributedCount() {
        return unattributedCount;
    }

    public Map<String, Map<String, Integer>> getPerMinuteSummaries() {
        return perMinuteSummaries;
    }
}
