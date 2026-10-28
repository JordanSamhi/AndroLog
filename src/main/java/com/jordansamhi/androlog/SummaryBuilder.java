package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androlog.utils.PackageScope;
import com.jordansamhi.androlog.instrumentation.StatementInstrumenter;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.util.Chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SummaryBuilder is a singleton class that aggregates information about various program components
 * using the Soot framework. It tracks details about methods, classes, Android components (like Activities, Services),
 * and Jimple statements within the application being analyzed.
 */
public class SummaryBuilder {
    private static SummaryBuilder instance;
    private SootUtils su;
    private boolean collectCode;
    private boolean collectCallGraph;
    private String targetPackage;
    private List<String> launcherActivities;
    private final Map<String, Integer> summary = new ConcurrentHashMap<>();
    private final Set<String> visitedComponents = new ConcurrentHashSet<>();
    private final Map<String, CoverageMethod> codeMethods = new ConcurrentHashMap<>();
    private final Map<String, CallSite> callSites = new ConcurrentHashMap<>();
    private final Set<String> graphMethods = new ConcurrentHashSet<>();

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
     * Enables collection of method bodies for a detailed report before building.
     */
    public void setCollectCode(boolean collectCode) {
        this.collectCode = collectCode;
    }

    public void setCollectCallGraph(boolean collectCallGraph) { this.collectCallGraph = collectCallGraph; }
    public boolean isCallGraphRequested() { return collectCallGraph; }
    public Map<String, CallSite> getCallSites() { return Collections.unmodifiableMap(callSites); }
    public Set<String> getGraphMethods() { return Collections.unmodifiableSet(graphMethods); }

    public void setLauncherActivities(List<String> activities) {
        launcherActivities = Collections.unmodifiableList(new ArrayList<>(activities));
    }

    /** Null means manifest metadata was not collected; an empty list means no launcher was declared. */
    public List<String> getLauncherActivities() { return launcherActivities; }

    private void collectCallSites(boolean includeLibraries) {
        addTransformation("jtp.callGraphBaseline", body -> {
            SootMethod method = body.getMethod();
            if (!method.isConcrete() || !isSelectedMethod(method, includeLibraries)) return;
            graphMethods.add(method.getSignature());
            int line = 0;
            for (Unit unit : body.getUnits()) {
                line++;
                Stmt statement = (Stmt) unit;
                if (!statement.containsInvokeExpr()) continue;
                InvokeExpr invoke = statement.getInvokeExpr();
                CallSite site = new CallSite(method.getSignature(), line,
                        invoke.getMethodRef().getSignature(), CallSite.kind(invoke));
                callSites.put(site.key(), site);
            }
        });
    }

    /**
     * Restricts collection and counts to the same exact package and subpackages as instrumentation.
     */
    public void setTargetPackage(String targetPackage) {
        this.targetPackage = PackageScope.normalize(targetPackage);
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    private boolean isSelectedMethod(SootMethod method, boolean includeLibraries) {
        if (isLogCheckerClass(method)) {
            return false;
        }
        SootClass clazz = method.getDeclaringClass();
        if (!PackageScope.includes(targetPackage, clazz.getPackageName())) {
            return false;
        }
        return includeLibraries || !LibrariesManager.v().isLibrary(clazz);
    }

    private void collectCodeMethods(boolean includeLibraries) {
        addTransformation("jtp.coverageCode", body -> {
            SootMethod method = body.getMethod();
            if (!method.isConcrete() || !isSelectedMethod(method, includeLibraries)) {
                return;
            }
            List<CoverageMethod.Statement> statements = new ArrayList<>();
            int line = 0;
            for (Unit unit : body.getUnits()) {
                Stmt statement = (Stmt) unit;
                statements.add(new CoverageMethod.Statement(++line, statement.toString(),
                        StatementInstrumenter.isEligible(statement)));
            }
            SootClass clazz = method.getDeclaringClass();
            String componentType = su.getComponentType(clazz);
            if (Constants.NON_COMPONENT.equals(componentType)) {
                componentType = null;
            }
            codeMethods.put(method.getSignature(), new CoverageMethod(clazz.getName(),
                    method.getSignature(), componentType, statements));
        });
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
    private synchronized void increment(String key) {
        summary.merge(key, 1, Integer::sum);
    }

    /**
     * Increments the component count in the summary if it has not been visited.
     *
     * @param type      The type of the component.
     * @param component The name of the component.
     */
    private synchronized void incrementComponent(String type, String component) {
        if (visitedComponents.add(type + component)) {
            increment(type);
        }
    }

    /**
     * Adds a transformation to gather method information.
     */
    private void getInfoMethods(boolean includeLibraries) {
        addTransformation("jtp.methods", b -> {
            if (!isSelectedMethod(b.getMethod(), includeLibraries)) {
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
            if (!isSelectedMethod(b.getMethod(), includeLibraries)) {
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
            if (!isSelectedMethod(b.getMethod(), includeLibraries)) {
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
            if (!isSelectedMethod(b.getMethod(), includeLibraries)) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            int cnt = 0;
            for (Unit u : units) {
                cnt++;
                Stmt stmt = (Stmt) u;
                if (!StatementInstrumenter.isEligible(stmt)) {
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
        if (collectCallGraph || collectCode) collectCallSites(includeLibraries);
        if (collectCode) {
            collectCodeMethods(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("c")) {
            summary.putIfAbsent("classes", 0);
            getInfoClasses(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("cp")) {
            summary.putIfAbsent("activities", 0);
            summary.putIfAbsent("services", 0);
            summary.putIfAbsent("broadcast-receivers", 0);
            summary.putIfAbsent("content-providers", 0);
            getInfoComponents(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("m")) {
            summary.putIfAbsent("methods", 0);
            getInfoMethods(includeLibraries);
        }
        if (CommandLineOptions.v().hasOption("s")) {
            summary.putIfAbsent("statements", 0);
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

    /**
     * Returns a stable, immutable snapshot of the collected method bodies.
     */
    public List<CoverageMethod> getCodeMethods() {
        List<CoverageMethod> methods = new ArrayList<>(codeMethods.values());
        methods.sort(Comparator.comparing(CoverageMethod::getClassName)
                .thenComparing(CoverageMethod::getSignature));
        return Collections.unmodifiableList(methods);
    }

    public Set<String> getVisitedComponents() { return visitedComponents; }
}
