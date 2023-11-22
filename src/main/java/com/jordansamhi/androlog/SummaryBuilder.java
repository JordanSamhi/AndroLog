package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.jordansamhi.androspecter.SootUtils;
import soot.*;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SummaryBuilder is a singleton class designed to analyze and summarize
 * different aspects of a program using Soot, a framework for analyzing and
 * transforming Java and Android applications. It can track and summarize
 * methods, classes, components, and statements. It also supports outputting
 * the summary to a file or printing it, and can load a summary from a JSON file.
 */
public class SummaryBuilder {
    private static SummaryBuilder instance;
    private SootUtils su;
    private String output;
    private final Map<String, Integer> summary = new HashMap<>();
    private final Set<SootClass> visitedItems = new HashSet<>();

    private SummaryBuilder() {
    }

    /**
     * Provides access to the singleton instance of the class.
     * If the instance does not exist, it is created.
     *
     * @return The singleton instance of SummaryBuilder.
     */
    public static SummaryBuilder v() {
        if (instance == null) {
            instance = new SummaryBuilder();
        }
        return instance;
    }

    /**
     * Sets the output file path where the summary will be written.
     *
     * @param output The file path for the output.
     */
    public void setOutput(String output) {
        this.output = output;
    }

    /**
     * Sets the SootUtils instance for this SummaryBuilder.
     *
     * @param su The SootUtils instance.
     */
    public void setSootUtils(SootUtils su) {
        this.su = su;
    }

    /**
     * Adds a transformation to the Soot pack manager with specified logic.
     *
     * @param phaseName The name of the transformation phase.
     * @param logic     The consumer logic to be applied to the Body of the method.
     */
    private void addTransformation(String phaseName, Consumer<Body> logic) {
        PackManager.v().getPack("jtp").add(new Transform(phaseName, new BodyTransformer() {
            @Override
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                logic.accept(b);
            }
        }));
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
     * Processes an item (class) and increments its count in the summary.
     *
     * @param sc   The SootClass to be processed.
     * @param type The type of the item to be incremented in the summary.
     */
    private void processItem(SootClass sc, String type) {
        if (visitedItems.add(sc)) {
            increment(type);
        }
    }

    /**
     * Retrieves and processes information about methods.
     */
    private void getInfoMethods() {
        addTransformation("jtp.methods", b -> processItem(b.getMethod().getDeclaringClass(), "methods"));
    }

    /**
     * Retrieves and processes information about classes.
     */
    private void getInfoClasses() {
        addTransformation("jtp.classes", b -> processItem(b.getMethod().getDeclaringClass(), "classes"));
    }

    /**
     * Retrieves and processes information about different components.
     */
    private void getInfoComponents() {
        addTransformation("jtp.components", b -> {
            SootClass sc = b.getMethod().getDeclaringClass();
            String actualComponentType = su.getComponentType(sc);
            processItem(sc, actualComponentType);
        });
    }

    /**
     * Retrieves and processes information about statements.
     */
    private void getInfoStatements() {
        addTransformation("jtp.statements", b -> increment("statements"));
    }

    /**
     * Executes the building process for gathering information and summarizing
     * various components of the program.
     */
    public void build() {
        getInfoClasses();
        getInfoComponents();
        getInfoMethods();
        getInfoStatements();
        PackManager.v().runPacks();
    }

    /**
     * Writes the summary to a specified file.
     */
    public void writeSummary() {
        writeOrPrintSummary(true);
    }

    /**
     * Prints the summary to the console.
     */
    public void printSummary() {
        writeOrPrintSummary(false);
    }

    /**
     * Writes or prints the summary based on the given flag.
     *
     * @param

    toFile Flag indicating whether to write to a file (true) or print (false).
     */
    private void writeOrPrintSummary(boolean toFile) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(summary);

        if (toFile) {
            try (FileWriter writer = new FileWriter(this.output)) {
                writer.write(json);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println(json);
        }
    }

    /**
     * Loads the summary data from a JSON file.
     *
     * @param filePath The path to the JSON file.
     */
    public void loadFromJsonFile(String filePath) {
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Integer>>() {}.getType();

        try (FileReader reader = new FileReader(filePath)) {
            summary.putAll(gson.fromJson(reader, type));
        } catch (IOException e) {
            e.printStackTrace();
        }
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