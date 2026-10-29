package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androlog.CustomLogPlan;
import com.jordansamhi.androlog.LocalVariableAvailability;
import com.jordansamhi.androlog.runtime.CustomLogRuntime;
import soot.*;
import soot.jimple.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** User-authored messages and typed local values emitted at explicitly selected points. */
public final class CustomLogInstrumenter {
    public static final String RUNTIME_CLASS = "com.jordansamhi.androlog.runtime.CustomLogRuntime";
    private final String tag;
    private final Map<String, List<CustomLogPlan.Point>> requested = new HashMap<>();
    private final Map<Body, List<Selected>> captured = new ConcurrentHashMap<>();
    private final Set<String> inserted = ConcurrentHashMap.newKeySet();
    private final SootMethodRef log;
    private final boolean dynamic;
    private SootClass runtime;

    public CustomLogInstrumenter(String tag, CustomLogPlan plan) throws IOException {
        plan.validate(); this.tag = tag;
        boolean values = false;
        for (CustomLogPlan.Point point : plan.points) {
            if (point.variables != null && !point.variables.isEmpty()) {
                values = true;
                if (point.id == null) point.id = UUID.randomUUID().toString().replace("-", "");
            }
            requested.computeIfAbsent(point.method, key -> new ArrayList<>()).add(point);
        }
        dynamic = values;
        SootClass logger = Scene.v().forceResolve("android.util.Log", SootClass.SIGNATURES);
        log = Scene.v().makeMethodRef(logger, "i", Arrays.asList(RefType.v("java.lang.String"), RefType.v("java.lang.String")), IntType.v(), true);
    }

    public static boolean isEligible(Stmt statement) { return !(statement instanceof IdentityStmt || statement instanceof MonitorStmt); }

    public void registerCapture() {
        if (dynamic) loadRuntime();
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologCustomLogCapture", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) { capture(body); }
        }));
    }

    public void registerInstrumentation() {
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologCustomLogs", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) { instrument(body); }
        }));
    }

    void capture(Body body) {
        List<CustomLogPlan.Point> points = requested.get(body.getMethod().getSignature());
        if (points == null) return;
        List<Unit> units = new ArrayList<>(); for (Unit unit : body.getUnits()) units.add(unit);
        List<Selected> selections = new ArrayList<>();
        LocalVariableAvailability available = null;
        for (CustomLogPlan.Point point : points) {
            if (point.index > units.size()) throw stale(point);
            Unit unit = units.get(point.index - 1);
            if (!unit.toString().equals(point.statement) || !isEligible((Stmt) unit)) throw stale(point);
            Map<String, Local> variables = new LinkedHashMap<>();
            if (point.variables != null && !point.variables.isEmpty()) {
                if (available == null) available = new LocalVariableAvailability(body);
                for (CustomLogPlan.Variable variable : point.variables) {
                    Local local = available.resolve(unit, variable);
                    if (local == null) throw new IllegalArgumentException("Local variable " + variable.name + " (" + variable.type
                            + ") is not initialized before this statement. Browse the APK and select the variable again.");
                    variables.put(variable.name, local);
                }
            }
            selections.add(new Selected(unit, point, variables));
        }
        captured.put(body, selections);
    }

    void instrument(Body body) {
        List<Selected> selections = captured.get(body);
        if (selections == null) return;
        for (Selected selected : selections) {
            if (!body.getUnits().contains(selected.unit)) throw stale(selected.point);
            if (!selected.variables.isEmpty()) {
                insertValues(body, selected);
                inserted.add(key(selected.point));
                continue;
            }
            // Log.i is deliberate: the coverage logger suppresses duplicates, whereas a custom
            // message should be observed each time this point is reached, including loop iterations.
            Unit probe = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(log,
                    StringConstant.v(tag), StringConstant.v("CUSTOM=" + selected.point.message)));
            body.getUnits().insertBefore(probe, selected.unit);
            if (selected.point.id != null) {
                Unit marker = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(log,
                        StringConstant.v(tag), StringConstant.v("CUSTOM_POINT=" + selected.point.id)));
                body.getUnits().insertBefore(marker, selected.unit);
            }
            inserted.add(key(selected.point));
        }
        body.validate();
    }

    private void insertValues(Body body, Selected selected) {
        RefType builder = RefType.v("java.lang.StringBuilder"), string = RefType.v("java.lang.String");
        if (selected.variables.size() == 1) {
            Local value = selected.variables.values().iterator().next();
            if (objectValue(value.getType()) && selected.point.message.equals("{" + value.getName() + "}")) {
                Unit probe = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(
                        runtimeRef("logObject", Arrays.asList(string, string, string, RefType.v("java.lang.Object")), VoidType.v()),
                        StringConstant.v(tag), StringConstant.v(selected.point.id), StringConstant.v(selected.point.message), value));
                body.getUnits().insertBefore(probe, selected.unit);
                return;
            }
        }
        String name = "$andrologValues";
        Set<String> names = new HashSet<>(); for (Local local : body.getLocals()) names.add(local.getName());
        while (names.contains(name)) name += "_";
        Local output = Jimple.v().newLocal(name, builder); body.getLocals().add(output);
        List<Unit> probes = new ArrayList<>();
        probes.add(Jimple.v().newAssignStmt(output, Jimple.v().newStaticInvokeExpr(runtimeRef("create", Collections.emptyList(), builder))));
        String template = selected.point.message;
        int offset = 0;
        while (offset < template.length()) {
            int next = template.length(); String variable = null;
            for (String candidate : selected.variables.keySet()) {
                int position = template.indexOf("{" + candidate + "}", offset);
                if (position >= 0 && position < next) { next = position; variable = candidate; }
            }
            if (next > offset) probes.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(
                    runtimeRef("text", Arrays.asList(builder, string), VoidType.v()), output, StringConstant.v(template.substring(offset, next)))));
            if (variable == null) break;
            Local local = selected.variables.get(variable);
            Type valueType = objectValue(local.getType()) ? RefType.v("java.lang.Object") : local.getType();
            probes.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(
                    runtimeRef("value", Arrays.asList(builder, valueType), VoidType.v()), output, local)));
            offset = next + variable.length() + 2;
        }
        probes.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(
                runtimeRef("write", Arrays.asList(string, string, string, builder), VoidType.v()),
                StringConstant.v(tag), StringConstant.v(selected.point.id), StringConstant.v(template), output)));
        body.getUnits().insertBefore(probes, selected.unit);
    }

    private static boolean objectValue(Type type) {
        return type instanceof ArrayType || (type instanceof RefType && !type.toString().equals("java.lang.String"));
    }

    private SootMethodRef runtimeRef(String name, List<Type> parameters, Type result) {
        SootClass owner = runtime == null ? Scene.v().forceResolve(RUNTIME_CLASS, SootClass.SIGNATURES) : runtime;
        return Scene.v().makeMethodRef(owner, name, parameters, result, true);
    }

    private void loadRuntime() {
        if (runtime != null) throw new IllegalStateException("Custom log capture is already registered.");
        if (Scene.v().containsClass(RUNTIME_CLASS) && !Scene.v().getSootClass(RUNTIME_CLASS).isPhantom()) {
            throw new IllegalStateException("APK already contains the reserved custom logging class " + RUNTIME_CLASS);
        }
        SourceLocator.v().additionalClassLoader(new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                return name.equals(RUNTIME_CLASS.replace('.', '/') + ".class")
                        ? CustomLogRuntime.class.getClassLoader().getResourceAsStream(name) : null;
            }
        });
        runtime = Scene.v().forceResolve(RUNTIME_CLASS, SootClass.BODIES);
        if (runtime.isPhantom()) throw new IllegalStateException("Cannot load bundled custom logging runtime.");
        runtime.setLibraryClass();
        for (SootMethod method : runtime.getMethods()) if (method.isConcrete()) method.retrieveActiveBody();
        SootMethod emit = runtime.getMethod("void emit(java.lang.String,java.lang.String)");
        JimpleBody body = Jimple.v().newBody(emit); emit.setActiveBody(body);
        Local tag = Jimple.v().newLocal("tag", RefType.v("java.lang.String")), message = Jimple.v().newLocal("message", RefType.v("java.lang.String"));
        body.getLocals().add(tag); body.getLocals().add(message);
        body.getUnits().add(Jimple.v().newIdentityStmt(tag, Jimple.v().newParameterRef(tag.getType(), 0)));
        body.getUnits().add(Jimple.v().newIdentityStmt(message, Jimple.v().newParameterRef(message.getType(), 1)));
        body.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(log, tag, message)));
        body.getUnits().add(Jimple.v().newReturnVoidStmt()); body.validate();
    }

    /** Include helper code only after all original-app transformations have completed. */
    public void includeRuntimeClasses() { if (runtime != null) runtime.setApplicationClass(); }

    public void verifyComplete() throws IOException {
        for (List<CustomLogPlan.Point> points : requested.values()) for (CustomLogPlan.Point point : points) {
            if (!inserted.contains(key(point))) throw new IOException("Custom log point was not found: " + point.method + " at statement " + point.index + ". Browse the APK again.");
        }
    }

    private static String key(CustomLogPlan.Point point) { return point.method + "\n" + point.index; }
    private static IllegalArgumentException stale(CustomLogPlan.Point point) {
        return new IllegalArgumentException("Custom log point no longer matches " + point.method + " at statement " + point.index + ". Browse the APK again.");
    }
    private static final class Selected {
        final Unit unit; final CustomLogPlan.Point point;
        final Map<String, Local> variables;
        Selected(Unit unit, CustomLogPlan.Point point, Map<String, Local> variables) { this.unit = unit; this.point = point; this.variables = variables; }
    }
}
