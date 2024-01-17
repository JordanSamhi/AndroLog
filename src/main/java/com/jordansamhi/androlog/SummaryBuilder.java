package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SummaryBuilder is a singleton class that aggregates information about various program components
 * using the Soot framework. It tracks details about methods, classes, Android components (like Activities, Services),
 * and Jimple statements within the application being analyzed.
 */
public class SummaryBuilder {
    private static SummaryBuilder instance;
    private SootUtils su;
    private final Map<String, Integer> summary = new HashMap<>();
    private final Set<String> visitedComponents = new HashSet<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private SummaryBuilder() {
    }

    /**
     * Returns the singleton instance of SummaryBuilder.
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
     * Sets the SootUtils instance for use in the summary building process.
     *
     * @param su The SootUtils instance to set.
     */
    public void setSootUtils(SootUtils su) {
        this.su = su;
    }

    /**
     * Adds a transformation phase to the Soot PackManager.
     *
     * @param phaseName The name of the transformation phase.
     * @param logic     The logic to be applied in this transformation phase.
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
     * @param key The key to increment in the summary.
     */
    private void increment(String key) {
        summary.merge(key, 1, Integer::sum);
    }

    /**
     * Increments the component count in the summary if it has not been visited.
     *
     * @param type      The type of the component.
     * @param component The name of the component.
     */
    private void incrementComponent(String type, String component) {
        if (visitedComponents.add(type + component)) {
            increment(type);
        }
    }

    /**
     * Adds a transformation to gather method information.
     */
    private void getInfoMethods(boolean includeLibraries) {
        addTransformation("jtp.methods", b -> {
            if (isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            incrementComponent("methods", b.getMethod().getSignature());
        });
    }

    /**
     * Adds a transformation to gather class information.
     */
    private void getInfoClasses(boolean includeLibraries) {
        addTransformation("jtp.classes", b -> {
            if (isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            incrementComponent("classes", b.getMethod().getDeclaringClass().getName());
        });
    }

    private boolean isLogCheckerClass(SootMethod sm) {
        String className = sm.getDeclaringClass().getName();
        return className.equals(Constants.LOG_CHECKER_CLASS);
    }

    /**
     * Adds a transformation to gather information on Android components like Activities, Services, etc.
     */
    private void getInfoComponents(boolean includeLibraries) {
        addTransformation("jtp.components", b -> {
            SootClass sc = b.getMethod().getDeclaringClass();
            if (!includeLibraries && LibrariesManager.v().isLibrary(sc)) {
                return;
            }
            String actualComponentType = su.getComponentType(sc);
            switch (actualComponentType) {
                case "Activity":
                    incrementComponent("activities", sc.getName());
                    break;
                case "Service":
                    incrementComponent("services", sc.getName());
                    break;
                case "BroadcastReceiver":
                    incrementComponent("broadcast-receivers", sc.getName());
                    break;
                case "ContentProvider":
                    incrementComponent("content-providers", sc.getName());
                    break;
            }
        });
    }

    /**
     * Adds a transformation to gather statement information.
     */
    private void getInfoStatements(boolean includeLibraries) {
        addTransformation("jtp.statements", b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && LibrariesManager.v().isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            int cnt = 0;
            for (Unit u : units) {
                cnt++;
                Stmt stmt = (Stmt) u;
                if (stmt instanceof IdentityStmt) {
                    continue;
                }
                if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
                    continue;
                }
                if (stmt instanceof MonitorStmt) {
                    continue;
                }
                String stmt_log = String.format("STATEMENT=%s|%s|%d", b.getMethod(), stmt, cnt);
                incrementComponent("statements", stmt_log);
            }
        });
    }

    /**
     * Executes all transformation phases to build the summary.
     */
    public void build(boolean includeLibraries) {
        if (CommandLineOptions.v().hasOption("c")) {
            getInfoClasses(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("cp")) {
            getInfoComponents(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("m")) {
            getInfoMethods(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("s")) {
            getInfoStatements(includeLibraries);
        }
        PackManager.v().runPacks();
    }

    /**
     * Retrieves the generated summary of components.
     *
     * @return A map representing the summary of components.
     */
    public Map<String, Integer> getSummary() {
        return summary;
    }
}