package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Private constructor to prevent instantiation from outside the class.
     */
    private SummaryLogBuilder() {
    }

    /**
     * Provides access to the singleton instance of the class.
     * If the instance does not exist, it is created.
     *
     * @return The singleton instance of SummaryLogBuilder.
     */
    public static SummaryLogBuilder v() {
        if (instance == null) {
            instance = new SummaryLogBuilder();
        }
        return instance;
    }

    /**
     * Increments the count for a given key in the summary map.
     *
     * @param key The key whose count is to be incremented.
     */
    private void increment(String key) {
        summary.merge(key, 1, Integer::sum);
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
     * @param type The type of component (e.g., "methods", "classes").
     * @param component The name of the component.
     */
    private void incrementComponent(String type, String component) {
        if (visitedComponents.add(type + component)) {
            increment(type);
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
}