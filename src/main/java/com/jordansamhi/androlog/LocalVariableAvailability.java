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
        Set<Local> allocatedStrings = allocationDerivedStrings(body);
        for (Local local : body.getLocals()) if (supported(local.getType().toString()) && !allocatedStrings.contains(local)) locals.add(local);
        if (body.getUnits().isEmpty() || locals.isEmpty()) return;
        List<Unit> units = new ArrayList<>(); for (Unit unit : body.getUnits()) units.add(unit);
        Map<Local, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < locals.size(); i++) positions.put(locals.get(i), i);
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
        BitSet universe = new BitSet(); universe.set(0, locals.size());
        Map<Unit, BitSet> after = new IdentityHashMap<>();
        Map<Unit, BitSet> definitions = new IdentityHashMap<>();
        for (Unit unit : units) {
            BitSet defined = new BitSet();
            for (ValueBox box : unit.getDefBoxes()) {
                Integer index = positions.get(box.getValue()); if (index != null) defined.set(index);
            }
            definitions.put(unit, defined);
            before.put(unit, reachable.contains(unit) ? (BitSet) universe.clone() : new BitSet());
            after.put(unit, reachable.contains(unit) ? (BitSet) universe.clone() : new BitSet());
        }
        Deque<Unit> work = new ArrayDeque<>(reachable);
        Set<Unit> queued = identitySet(reachable);
        while (!work.isEmpty()) {
            Unit unit = work.removeFirst(); queued.remove(unit);
            BitSet input = (BitSet) universe.clone();
            if (unit == units.get(0)) input.clear();
            for (Unit pred : normal.getPredsOf(unit)) if (reachable.contains(pred)) input.and(after.get(pred));
            for (Unit pred : exceptional.getOrDefault(unit, Collections.emptySet())) if (reachable.contains(pred)) input.and(before.get(pred));
            BitSet output = (BitSet) input.clone(); output.or(definitions.get(unit));
            if (!input.equals(before.get(unit)) || !output.equals(after.get(unit))) {
                before.put(unit, input); after.put(unit, output);
                for (Unit next : successors.get(unit)) if (queued.add(next)) work.addLast(next);
            }
        }
    }

    public List<CustomLogPlan.Variable> at(Unit unit) {
        BitSet available = before.get(unit);
        List<CustomLogPlan.Variable> result = new ArrayList<>();
        if (available != null) for (int i = available.nextSetBit(0); i >= 0; i = available.nextSetBit(i + 1)) {
            Local local = locals.get(i); result.add(new CustomLogPlan.Variable(local.getName(), local.getType().toString()));
        }
        result.sort(Comparator.comparing(variable -> variable.name));
        return result;
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
        return Arrays.asList("byte", "short", "int", "long", "float", "double", "boolean", "java.lang.String").contains(type);
    }

    private static Set<Local> allocationDerivedStrings(Body body) {
        Set<Local> unsafe = Collections.newSetFromMap(new IdentityHashMap<Local, Boolean>());
        for (Unit unit : body.getUnits()) if (unit instanceof DefinitionStmt) {
            DefinitionStmt definition = (DefinitionStmt) unit;
            if (!(definition.getLeftOp() instanceof Local)) continue;
            Value source = definition.getRightOp();
            if ((source instanceof NewExpr && "java.lang.String".equals(((NewExpr) source).getBaseType().toString()))
                    || (source instanceof ThisRef && body.getMethod().isConstructor() && "java.lang.String".equals(source.getType().toString()))) unsafe.add((Local) definition.getLeftOp());
        }
        // Keep allocation-derived Strings out until constructor/alias analysis is supported.
        // A `new String` value is verifier-uninitialized even though its local has a definition.
        boolean changed;
        do {
            changed = false;
            for (Unit unit : body.getUnits()) if (unit instanceof AssignStmt) {
                AssignStmt assignment = (AssignStmt) unit;
                if (!(assignment.getLeftOp() instanceof Local)) continue;
                Value source = assignment.getRightOp();
                if (source instanceof CastExpr) source = ((CastExpr) source).getOp();
                if (source instanceof Local && unsafe.contains(source) && unsafe.add((Local) assignment.getLeftOp())) changed = true;
            }
        } while (changed);
        return unsafe;
    }

    private static Set<Unit> identitySet(Collection<Unit> initial) {
        Set<Unit> result = Collections.newSetFromMap(new IdentityHashMap<Unit, Boolean>()); result.addAll(initial); return result;
    }
}
