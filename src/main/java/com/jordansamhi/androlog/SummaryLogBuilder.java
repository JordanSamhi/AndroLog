package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;
import java.text.SimpleDateFormat;
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
    private final SimpleDateFormat minuteFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

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
        String minuteKey = extractMinuteKey(component);
        perMinuteSummaries.putIfAbsent(minuteKey, new HashMap<>());

        if (visitedComponents.add(type + extractContent(component))) {
            increment(type, summary);
            Map<String, Integer> minuteSummary = perMinuteSummaries.get(minuteKey);
            increment(type, minuteSummary);
        }
    }

    private String extractContent(String line) {
        Pattern pattern = Pattern.compile("=(.*)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Parse log line to get minute string like "07-03 11:05"
     */
    private String extractMinuteKey(String logLine) {
        try {
            String ts = logLine.substring(0, 18); // "MM-dd HH:mm:ss.SSS"
            return minuteFormat.format(minuteFormat.parse(ts));
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    public Map<String, Map<String, Integer>> getPerMinuteSummaries() {
        return perMinuteSummaries;
    }
}