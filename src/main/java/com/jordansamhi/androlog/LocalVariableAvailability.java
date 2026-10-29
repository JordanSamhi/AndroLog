package com.jordansamhi.androlog;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.BriefUnitGraph;

import java.util.*;

/** Locals whose values exist on every path arriving before an original statement. */
public final class LocalVariableAvailability {
    private final List<Local> locals = new ArrayList<>();
    private final Map<Unit, BitSet> before = new IdentityHashMap<>();

    public LocalVariableAvailability(Body body) {
        for (Local local : body.getLocals()) if (supported(local.getType().toString())) locals.add(local);
        // Every picker uses this same order; avoid sorting again for each statement.
        locals.sort(Comparator.comparing(Local::getName));
        if (body.getUnits().isEmpty() || locals.isEmpty()) return;
        List<Unit> units = new ArrayList<>(); for (Unit unit : body.getUnits()) units.add(unit);
        Map<Local, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < locals.size(); i++) positions.put(locals.get(i), i);
        int[] referencePositions = new int[locals.size()]; Arrays.fill(referencePositions, -1);
        List<Integer> referenceLocals = new ArrayList<>();
        for (int i = 0; i < locals.size(); i++) if (locals.get(i).getType() instanceof RefLikeType) {
            referencePositions[i] = referenceLocals.size(); referenceLocals.add(i);
        }
        BriefUnitGraph normal = new BriefUnitGraph(body);
        Map<Unit, Set<Unit>> exceptional = new IdentityHashMap<>(), successors = new IdentityHashMap<>();
        Map<Unit, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            indices.put(units.get(i), i);
            successors.put(units.get(i), identitySet(normal.getSuccsOf(units.get(i))));
        }
        // A handler can be entered before a protected assignment has produced its result.
        // Even non-throwing protected units are included conservatively.
        for (Trap trap : body.getTraps()) {
            Set<Unit> predecessors = exceptional.computeIfAbsent(trap.getHandlerUnit(), key -> identitySet(Collections.emptyList()));
            for (int i = indices.get(trap.getBeginUnit()); i < indices.get(trap.getEndUnit()); i++) {
                Unit unit = units.get(i); predecessors.add(unit); successors.get(unit).add(trap.getHandlerUnit());
            }
        }
        Set<Unit> reachable = identitySet(Collections.emptyList());
        Deque<Unit> visit = new ArrayDeque<>(); visit.add(units.get(0));
        while (!visit.isEmpty()) {
            Unit unit = visit.removeFirst();
            if (reachable.add(unit)) visit.addAll(successors.get(unit));
        }
        Map<Unit, Facts> inputs = new IdentityHashMap<>(), outputs = new IdentityHashMap<>();
        for (Unit unit : units) {
            inputs.put(unit, new Facts(locals.size(), referenceLocals.size(), reachable.contains(unit)));
            outputs.put(unit, new Facts(locals.size(), referenceLocals.size(), reachable.contains(unit)));
        }
        // IdentityHashMap iteration is arbitrary. Visit statements in body order
        // so ordinary forward edges see their predecessors' facts on the first pass.
        Deque<Unit> work = new ArrayDeque<>();
        for (Unit unit : units) if (reachable.contains(unit)) work.addLast(unit);
        Set<Unit> queued = identitySet(reachable);
        while (!work.isEmpty()) {
            Unit unit = work.removeFirst(); queued.remove(unit);
            Facts input = new Facts(locals.size(), referenceLocals.size(), unit != units.get(0));
            for (Unit pred : normal.getPredsOf(unit)) if (reachable.contains(pred)) input.intersect(outputs.get(pred));
            for (Unit pred : exceptional.getOrDefault(unit, Collections.emptySet())) if (reachable.contains(pred)) input.intersect(inputs.get(pred));
            Facts output = input.copy();
            if (unit instanceof DefinitionStmt) {
                DefinitionStmt definition = (DefinitionStmt) unit;
                Integer target = positions.get(definition.getLeftOp());
                if (target != null) {
                    Value source = definition.getRightOp();
                    int targetReference = referencePositions[target];
                    if (targetReference < 0) output.initialized.set(target);
                    else {
                        while (source instanceof CastExpr) source = ((CastExpr) source).getOp();
                        Integer sourcePosition = positions.get(source);
                        if (source instanceof Local) {
                            output.initialized.set(target, sourcePosition != null && input.initialized.get(sourcePosition));
                            int sourceReference = sourcePosition == null ? -1 : referencePositions[sourcePosition];
                            if (sourceReference >= 0) output.assignAlias(targetReference, sourceReference);
                            else output.detach(targetReference);
                        } else {
                            output.detach(targetReference);
                            output.initialized.set(target, !(source instanceof NewExpr)
                                    && !(source instanceof ThisRef && body.getMethod().isConstructor()));
                        }
                    }
                }
            }
            if (unit instanceof Stmt && ((Stmt) unit).containsInvokeExpr()) {
                InvokeExpr invoke = ((Stmt) unit).getInvokeExpr();
                if (invoke instanceof SpecialInvokeExpr && "<init>".equals(invoke.getMethodRef().name())) {
                    Integer receiver = positions.get(((SpecialInvokeExpr) invoke).getBase());
                    if (receiver != null && referencePositions[receiver] >= 0) {
                        int group = output.aliases[referencePositions[receiver]];
                        for (int i = 0; i < output.aliases.length; i++) if (output.aliases[i] == group) output.initialized.set(referenceLocals.get(i));
                    }
                }
            }
            if (!input.equals(inputs.get(unit)) || !output.equals(outputs.get(unit))) {
                inputs.put(unit, input); outputs.put(unit, output);
                for (Unit next : successors.get(unit)) if (queued.add(next)) work.addLast(next);
            }
        }
        for (Unit unit : units) before.put(unit, inputs.get(unit).initialized);
    }

    public List<CustomLogPlan.Variable> at(Unit unit) {
        BitSet available = before.get(unit);
        List<CustomLogPlan.Variable> result = new ArrayList<>();
        if (available != null) for (int i = available.nextSetBit(0); i >= 0; i = available.nextSetBit(i + 1)) {
            Local local = locals.get(i); result.add(new CustomLogPlan.Variable(local.getName(), local.getType().toString()));
        }
        return result;
    }

    /** Method-wide table; maskAt uses the indices in this stable, name-sorted list. */
    public List<CustomLogPlan.Variable> locals() {
        List<CustomLogPlan.Variable> result = new ArrayList<>();
        for (Local local : locals) result.add(new CustomLogPlan.Variable(local.getName(), local.getType().toString()));
        return result;
    }

    /** Compact little-endian availability bits, with an empty string meaning no locals. */
    public String maskAt(Unit unit) {
        BitSet available = before.get(unit);
        return available == null || available.isEmpty() ? "" : Base64.getEncoder().encodeToString(available.toByteArray());
    }

    public Local resolve(Unit unit, CustomLogPlan.Variable variable) {
        BitSet available = before.get(unit);
        if (available != null) for (int i = available.nextSetBit(0); i >= 0; i = available.nextSetBit(i + 1)) {
            Local local = locals.get(i);
            if (local.getName().equals(variable.name) && local.getType().toString().equals(variable.type)) return local;
        }
        return null;
    }

    public static boolean supported(String type) {
        if (type == null || type.isEmpty() || type.length() > 1024) return false;
        int dimensions = 0;
        while (type.endsWith("[]")) { if (++dimensions > 255) return false; type = type.substring(0, type.length() - 2); }
        if (Arrays.asList("byte", "short", "int", "long", "float", "double", "boolean").contains(type)) return true;
        if ("char".equals(type)) return dimensions > 0;
        if (Arrays.asList("void", "null", "null_type", "unknown", "bottom_type", "address").contains(type)) return false;
        return type.matches("[^\\s\\p{Cntrl}\\[\\];/(){}<>:.]+(?:\\.[^\\s\\p{Cntrl}\\[\\];/(){}<>:.]+)*");
    }

    /** Must facts: constructor success initializes only references guaranteed to alias its receiver. */
    private static final class Facts {
        final BitSet initialized;
        final int[] aliases;
        Facts(int locals, int references, boolean top) {
            initialized = new BitSet(locals); aliases = new int[references];
            if (top) initialized.set(0, locals);
            else for (int i = 0; i < references; i++) aliases[i] = i;
        }
        Facts(BitSet initialized, int[] aliases) { this.initialized = initialized; this.aliases = aliases; }
        Facts copy() { return new Facts((BitSet) initialized.clone(), aliases.clone()); }
        void intersect(Facts other) {
            initialized.and(other.initialized);
            Map<Long, Integer> groups = new HashMap<>();
            for (int i = 0; i < aliases.length; i++) {
                long pair = ((long) aliases[i] << 32) | (other.aliases[i] & 0xffffffffL);
                Integer representative = groups.get(pair);
                if (representative == null) { representative = i; groups.put(pair, representative); }
                aliases[i] = representative;
            }
        }
        void detach(int target) {
            int old = aliases[target], replacement = -1;
            for (int i = 0; i < aliases.length; i++) if (i != target && aliases[i] == old) { replacement = i; break; }
            if (replacement >= 0) for (int i = 0; i < aliases.length; i++) if (i != target && aliases[i] == old) aliases[i] = replacement;
            aliases[target] = target;
        }
        void assignAlias(int target, int source) {
            if (target == source) return;
            detach(target);
            int old = aliases[source], replacement = Math.min(target, old);
            for (int i = 0; i < aliases.length; i++) if (i == target || aliases[i] == old) aliases[i] = replacement;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Facts)) return false;
            Facts other = (Facts) value;
            return initialized.equals(other.initialized) && Arrays.equals(aliases, other.aliases);
        }
        @Override public int hashCode() { return 31 * initialized.hashCode() + Arrays.hashCode(aliases); }
    }

    private static Set<Unit> identitySet(Collection<Unit> initial) {
        Set<Unit> result = Collections.newSetFromMap(new IdentityHashMap<Unit, Boolean>()); result.addAll(initial); return result;
    }
}
