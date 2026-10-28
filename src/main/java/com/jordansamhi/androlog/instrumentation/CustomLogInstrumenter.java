package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androlog.CustomLogPlan;
import soot.*;
import soot.jimple.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** User-authored literal messages, emitted at every arrival at an explicitly selected point. */
public final class CustomLogInstrumenter {
    private final String tag;
    private final Map<String, List<CustomLogPlan.Point>> requested = new HashMap<>();
    private final Map<Body, List<Selected>> captured = new ConcurrentHashMap<>();
    private final Set<String> inserted = ConcurrentHashMap.newKeySet();
    private final SootMethodRef log;

    public CustomLogInstrumenter(String tag, CustomLogPlan plan) throws IOException {
        plan.validate(); this.tag = tag;
        for (CustomLogPlan.Point point : plan.points) requested.computeIfAbsent(point.method, key -> new ArrayList<>()).add(point);
        SootClass logger = Scene.v().forceResolve("android.util.Log", SootClass.SIGNATURES);
        log = Scene.v().makeMethodRef(logger, "i", Arrays.asList(RefType.v("java.lang.String"), RefType.v("java.lang.String")), IntType.v(), true);
    }

    public static boolean isEligible(Stmt statement) { return !(statement instanceof IdentityStmt || statement instanceof MonitorStmt); }

    public void registerCapture() {
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
        for (CustomLogPlan.Point point : points) {
            if (point.index > units.size()) throw stale(point);
            Unit unit = units.get(point.index - 1);
            if (!unit.toString().equals(point.statement) || !isEligible((Stmt) unit)) throw stale(point);
            selections.add(new Selected(unit, point));
        }
        captured.put(body, selections);
    }

    void instrument(Body body) {
        List<Selected> selections = captured.get(body);
        if (selections == null) return;
        for (Selected selected : selections) {
            if (!body.getUnits().contains(selected.unit)) throw stale(selected.point);
            // Log.i is deliberate: the coverage logger suppresses duplicates, whereas a custom
            // message should be observed each time this point is reached, including loop iterations.
            Unit probe = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(log,
                    StringConstant.v(tag), StringConstant.v("CUSTOM=" + selected.point.message)));
            body.getUnits().insertBefore(probe, selected.unit);
            inserted.add(key(selected.point));
        }
        body.validate();
    }

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
        Selected(Unit unit, CustomLogPlan.Point point) { this.unit = unit; this.point = point; }
    }
}
