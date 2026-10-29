package com.jordansamhi.androlog;

import com.jordansamhi.androlog.instrumentation.MethodCallInstrumenter;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.TrapUnitGraph;
import soot.toolkits.scalar.LocalDefs;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;

import java.util.*;

/** Reads the probes already in a DEX body without relying on a machine-local instrumentation plan. */
public final class InstrumentedCode {
    private static final String CUSTOM_LOG = "int i(java.lang.String,java.lang.String)";
    private static final String CUSTOM_RUNTIME = "com.jordansamhi.androlog.runtime.CustomLogRuntime";
    private static final String CUSTOM_WRITE = "void write(java.lang.String,java.lang.String,java.lang.String,java.lang.StringBuilder)";
    private static final String CUSTOM_OBJECT = "void logObject(java.lang.String,java.lang.String,java.lang.String,java.lang.Object)";
    private static final String BEFORE = "long before(java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.Object)";
    private static final String ENTER = "void enter(java.lang.String,java.lang.Object)";
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("static", "interface", "virtual", "special", "constructor", "dynamic"));
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("STATEMENT", "statements"); CATEGORIES.put("METHOD", "methods"); CATEGORIES.put("CLASS", "classes");
        CATEGORIES.put("ACTIVITY", "activities"); CATEGORIES.put("SERVICE", "services");
        CATEGORIES.put("BROADCASTRECEIVER", "broadcast-receivers"); CATEGORIES.put("CONTENTPROVIDER", "content-providers");
    }

    private InstrumentedCode() { }

    public static boolean isHelper(String name) {
        return Constants.LOG_CHECKER_CLASS.equals(name) || MethodCallInstrumenter.RUNTIME_CLASS.equals(name)
                || name.startsWith(MethodCallInstrumenter.RUNTIME_CLASS + "$") || CUSTOM_RUNTIME.equals(name);
    }

    public static final class Result {
        public final Map<String, Set<String>> expected = new LinkedHashMap<>();
        public CoverageMethod code;
        public final List<CallSite> callSites = new ArrayList<>();
        public final Map<String, Integer> callSiteRows = new LinkedHashMap<>();
        public final Set<String> graphMethods = new LinkedHashSet<>();
        public final List<Map<String, Object>> customPoints = new ArrayList<>();
        public boolean foundProbes;
    }

    public static Result inspect(Body body, String tag, String componentType) {
        Result result = new Result();
        String method = body.getMethod().getSignature();
        String owner = body.getMethod().getDeclaringClass().getName();
        if (isHelper(owner)) return result;
        List<Unit> units = new ArrayList<>();
        for (Unit unit : body.getUnits()) units.add(unit);
        Set<Unit> hidden = identitySet();
        Map<Unit, String> statementProbes = new IdentityHashMap<>();
        Map<Unit, CallSite> callProbes = new IdentityHashMap<>();
        Map<Unit, Custom> customs = new IdentityHashMap<>();
        Map<Unit, String> markers = new IdentityHashMap<>();
        Set<Unit> applicationDefinitions = identitySet();
        // Many methods have no probes; avoid building def-use graphs for those bodies.
        boolean candidates = false;
        for (Unit unit : units) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;
            String clazz = stmt.getInvokeExpr().getMethodRef().declaringClass().getName();
            if (Constants.LOG_CHECKER_CLASS.equals(clazz) || MethodCallInstrumenter.RUNTIME_CLASS.equals(clazz)
                    || CUSTOM_RUNTIME.equals(clazz) || "android.util.Log".equals(clazz)) { candidates = true; break; }
        }
        Resolver resolver = candidates ? new Resolver(body) : null;
        for (Unit unit : units) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;
            InvokeExpr invoke = stmt.getInvokeExpr();
            if (!(invoke instanceof StaticInvokeExpr)) continue;
            String clazz = invoke.getMethodRef().declaringClass().getName();
            String signature = invoke.getMethodRef().getSubSignature().toString();
            if (Constants.LOG_CHECKER_CLASS.equals(clazz) && Constants.SUB_SIG_LOG.equals(signature)) {
                hidden.add(unit);
                String message = resolver.string(invoke.getArg(0), unit);
                if (!Objects.equals(tag, resolver.string(invoke.getArg(1), unit)) || message == null) continue;
                int equals = message.indexOf('=');
                if (equals < 0) continue;
                String category = CATEGORIES.get(message.substring(0, equals));
                if (category == null) continue;
                String payload = message.substring(equals + 1);
                if ("statements".equals(category)) {
                    if (!validStatement(payload, method)) continue;
                    statementProbes.put(unit, payload);
                    payload = message;
                } else if ("methods".equals(category)) {
                    if (!payload.equals(method)) continue;
                } else {
                    if (!"classes".equals(category)) {
                        int callback = payload.indexOf('|');
                        if (callback >= 0) payload = payload.substring(0, callback);
                    }
                    if (!payload.equals(owner)) continue;
                }
                result.expected.computeIfAbsent(category, key -> new LinkedHashSet<>()).add(payload);
                result.foundProbes = true;
            } else if (MethodCallInstrumenter.RUNTIME_CLASS.equals(clazz)) {
                if (BEFORE.equals(signature)) {
                    hidden.add(unit);
                    String actualTag = resolver.string(invoke.getArg(0), unit);
                    String caller = resolver.string(invoke.getArg(1), unit);
                    Integer index = resolver.integer(invoke.getArg(2), unit);
                    String declared = resolver.string(invoke.getArg(3), unit);
                    String kind = resolver.string(invoke.getArg(4), unit);
                    if (Objects.equals(tag, actualTag) && method.equals(caller) && index != null && index > 0
                            && declared != null && declared.startsWith("<") && declared.endsWith(">") && KINDS.contains(kind)) {
                        CallSite site = new CallSite(caller, index, declared, kind);
                        result.callSites.add(site); callProbes.put(unit, site);
                        result.graphMethods.add(caller); result.foundProbes = true;
                    }
                } else if (ENTER.equals(signature)) {
                    hidden.add(unit);
                    if (method.equals(resolver.string(invoke.getArg(0), unit))) result.graphMethods.add(method);
                } else if ("void after(long)".equals(signature) || "void caught()".equals(signature)) {
                    hidden.add(unit);
                }
            } else if (CUSTOM_RUNTIME.equals(clazz)) {
                if (CUSTOM_WRITE.equals(signature) || CUSTOM_OBJECT.equals(signature)) {
                    hidden.add(unit);
                    if (CUSTOM_OBJECT.equals(signature)) protectDefinitions(invoke.getArg(3), unit, applicationDefinitions, resolver);
                    String id = resolver.string(invoke.getArg(1), unit);
                    String template = resolver.string(invoke.getArg(2), unit);
                    if (Objects.equals(tag, resolver.string(invoke.getArg(0), unit))
                            && id != null && id.matches("[a-f0-9]{32}") && template != null) {
                        Custom point = new Custom(template); point.id = id; point.dynamic = true;
                        customs.put(unit, point); result.foundProbes = true;
                    }
                } else if ("java.lang.StringBuilder create()".equals(signature)
                        || "void text(java.lang.StringBuilder,java.lang.String)".equals(signature)) {
                    hidden.add(unit);
                } else if (signature.matches("void value\\(java\\.lang\\.StringBuilder,(byte|short|int|long|float|double|boolean|java\\.lang\\.String|java\\.lang\\.Object)\\)")) {
                    hidden.add(unit);
                    // A local read only by a custom value is still application code, not probe setup.
                    protectDefinitions(invoke.getArg(1), unit, applicationDefinitions, resolver);
                }
            } else if ("android.util.Log".equals(clazz) && CUSTOM_LOG.equals(signature)
                    && Objects.equals(tag, resolver.string(invoke.getArg(0), unit))) {
                String message = resolver.string(invoke.getArg(1), unit);
                if (message != null && message.startsWith("CUSTOM=")) {
                    customs.put(unit, new Custom(message.substring("CUSTOM=".length())));
                    hidden.add(unit); result.foundProbes = true;
                } else if (message != null && message.matches("CUSTOM_REACHED=[a-f0-9]{32}")) {
                    Custom point = new Custom(null); point.id = message.substring("CUSTOM_REACHED=".length()); point.reached = true;
                    customs.put(unit, point); hidden.add(unit); result.foundProbes = true;
                } else if (message != null && message.matches("CUSTOM_POINT=[a-f0-9]{32}")) {
                    markers.put(unit, message.substring("CUSTOM_POINT=".length()));
                    // A marker is hidden only once it is paired with its CUSTOM message.
                }
            }
        }
        if (resolver != null) {
            Set<Unit> pairingHidden = identitySet(); pairingHidden.addAll(hidden); pairingHidden.addAll(markers.keySet());
            hideProbeSetup(units, pairingHidden, applicationDefinitions, resolver);
            for (int i = 0; i < units.size(); i++) {
                Custom custom = customs.get(units.get(i)); if (custom == null || custom.id != null) continue;
                for (int j = i + 1; j < units.size(); j++) {
                    Unit next = units.get(j);
                    if (markers.containsKey(next)) { custom.id = markers.get(next); hidden.add(next); break; }
                    if (customs.containsKey(next) || !pairingHidden.contains(next)) break;
                }
            }
            hideProbeSetup(units, hidden, applicationDefinitions, resolver);
        }
        Map<Unit, List<String>> payloadsByUnit = new IdentityHashMap<>();
        Map<Unit, Unit> nextVisible = new IdentityHashMap<>();
        Unit visible = null;
        for (int i = units.size() - 1; i >= 0; i--) {
            Unit unit = units.get(i);
            if (!hidden.contains(unit)) visible = unit;
            nextVisible.put(unit, visible);
        }
        for (Unit unit : units) {
            String payload = statementProbes.get(unit);
            Unit target = nextVisible.get(unit);
            if (payload != null && target != null) payloadsByUnit.computeIfAbsent(target, key -> new ArrayList<>()).add(payload);
        }
        // Render detached clones so branches no longer print an injected logging call as their target.
        Map<Unit, Unit> clones = new IdentityHashMap<>();
        for (Unit unit : units) if (!hidden.contains(unit)) clones.put(unit, (Unit) unit.clone());
        for (Unit clone : clones.values()) for (UnitBox box : clone.getUnitBoxes()) {
            Unit target = nextVisible.get(box.getUnit());
            if (target != null && clones.containsKey(target)) box.setUnit(clones.get(target));
        }
        List<CoverageMethod.Statement> statements = new ArrayList<>();
        Map<Unit, Integer> rowByUnit = new IdentityHashMap<>();
        Map<Unit, String> textByUnit = new IdentityHashMap<>();
        for (Unit unit : units) {
            if (hidden.contains(unit)) continue;
            List<String> payloads = payloadsByUnit.getOrDefault(unit, Collections.emptyList());
            // DEX optimizations can coalesce original operations. Preserve each expected
            // point as an explicit row instead of silently losing its coverage identity.
            for (int p = 0; p < payloads.size() - 1; p++) {
                String payload = payloads.get(p);
                statements.add(new CoverageMethod.Statement(statements.size() + 1, originalText(payload, method), true, payload));
            }
            String text = clones.get(unit).toString();
            int row = statements.size() + 1;
            String payload = payloads.isEmpty() ? null : payloads.get(payloads.size() - 1);
            statements.add(new CoverageMethod.Statement(row, text, payload != null, payload));
            rowByUnit.put(unit, row); textByUnit.put(unit, text);
        }
        for (Unit unit : units) {
            Unit target = nextVisible.get(unit);
            Integer row = rowByUnit.get(target);
            Custom custom = customs.get(unit);
            if (custom != null) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", custom.id); point.put("method", method);
                point.put("index", row == null ? 0 : row); point.put("statement", textByUnit.get(target));
                if (!custom.reached) point.put("message", custom.message);
                point.put("kind", custom.reached ? "reached" : custom.dynamic ? "value" : "message");
                if (custom.dynamic) point.put("dynamic", true);
                result.customPoints.add(point);
            }
            CallSite site = callProbes.get(unit);
            if (site != null && row != null) result.callSiteRows.put(site.key(), row);
        }
        result.code = new CoverageMethod(owner, method, componentType, statements);
        return result;
    }

    private static boolean validStatement(String payload, String method) {
        if (!payload.startsWith(method + "|")) return false;
        int separator = payload.lastIndexOf('|');
        if (separator <= method.length() + 1) return false;
        try { return Integer.parseInt(payload.substring(separator + 1)) > 0; }
        catch (NumberFormatException error) { return false; }
    }

    private static String originalText(String payload, String method) {
        return payload.substring(method.length() + 1, payload.lastIndexOf('|'));
    }

    /** Remove only side-effect-free local setup whose every use belongs to hidden instrumentation. */
    private static void hideProbeSetup(List<Unit> units, Set<Unit> hidden, Set<Unit> applicationDefinitions, Resolver resolver) {
        Set<Unit> candidates = identitySet();
        Deque<Unit> work = new ArrayDeque<>(hidden);
        while (!work.isEmpty()) {
            Unit unit = work.removeFirst();
            for (ValueBox box : unit.getUseBoxes()) {
                if (!(box.getValue() instanceof Local)) continue;
                for (Unit definition : resolver.defs.getDefsOfAt((Local) box.getValue(), unit)) {
                    if (applicationDefinitions.contains(definition)) continue;
                    if (!(definition instanceof AssignStmt)) continue;
                    AssignStmt assignment = (AssignStmt) definition;
                    if (!(assignment.getLeftOp() instanceof Local)) continue;
                    Value value = assignment.getRightOp();
                    if (!(value instanceof Constant || value instanceof Local)) continue;
                    if (candidates.add(definition)) work.add(definition);
                }
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Iterator<Unit> iterator = candidates.iterator(); iterator.hasNext();) {
                Unit definition = iterator.next();
                for (UnitValueBoxPair use : resolver.uses.getUsesOf(definition)) {
                    if (!hidden.contains(use.getUnit()) && !candidates.contains(use.getUnit())) {
                        iterator.remove(); changed = true; break;
                    }
                }
            }
        } while (changed);
        hidden.addAll(candidates);
    }

    private static void protectDefinitions(Value value, Unit at, Set<Unit> protectedUnits, Resolver resolver) {
        if (!(value instanceof Local)) return;
        for (Unit definition : resolver.defs.getDefsOfAt((Local) value, at)) {
            if (!protectedUnits.add(definition) || !(definition instanceof AssignStmt)) continue;
            Value source = ((AssignStmt) definition).getRightOp();
            if (source instanceof Local) protectDefinitions(source, definition, protectedUnits, resolver);
        }
    }

    private static Set<Unit> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<Unit, Boolean>()); }
    private static final class Custom {
        final String message; String id; boolean dynamic, reached;
        Custom(String message) { this.message = message; }
    }
    private static final class Resolver {
        final LocalDefs defs;
        final SimpleLocalUses uses;
        Resolver(Body body) {
            // TrapUnitGraph conservatively includes exception-handler predecessors without
            // requiring the complete platform exception hierarchy for def-use construction.
            TrapUnitGraph graph = new TrapUnitGraph(body);
            defs = new SimpleLocalDefs(graph);
            uses = new SimpleLocalUses(graph, defs);
        }
        String string(Value value, Unit at) {
            Constant constant = constant(value, at, identitySet());
            return constant instanceof StringConstant ? ((StringConstant) constant).value : null;
        }
        Integer integer(Value value, Unit at) {
            Constant constant = constant(value, at, identitySet());
            return constant instanceof IntConstant ? ((IntConstant) constant).value : null;
        }
        Constant constant(Value value, Unit at, Set<Unit> visited) {
            if (value instanceof Constant) return (Constant) value;
            if (!(value instanceof Local)) return null;
            List<Unit> definitions = defs.getDefsOfAt((Local) value, at);
            if (definitions.isEmpty()) return null;
            Constant common = null;
            for (Unit definition : definitions) {
                if (!(definition instanceof AssignStmt) || visited.contains(definition)) return null;
                Set<Unit> branch = identitySet(); branch.addAll(visited); branch.add(definition);
                Constant resolved = constant(((AssignStmt) definition).getRightOp(), definition, branch);
                if (resolved == null || (common != null && !common.equals(resolved))) return null;
                common = resolved;
            }
            return common;
        }
    }
}
