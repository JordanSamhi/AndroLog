package com.jordansamhi.androlog;

import org.junit.*;
import soot.*;
import soot.jimple.*;
import soot.options.Options;
import java.util.*;
import static org.junit.Assert.*;

public class LocalVariableAvailabilityTest {
    private JimpleBody body;
    @Before public void setup() {
        G.reset(); Options.v().set_allow_phantom_refs(true);
        SootClass owner = new SootClass("example.Availability", Modifier.PUBLIC); Scene.v().addClass(owner);
        SootMethod method = new SootMethod("run", Collections.singletonList(IntType.v()), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method); body = Jimple.v().newBody(method); method.setActiveBody(body);
    }
    @After public void cleanup() { G.reset(); }

    @Test public void localsBecomeAvailableOnlyAfterTheirDefinitionsAndOnlySupportedTypesAreExposed() {
        Local parameter = local("i0", IntType.v()), string = local("text", RefType.v("java.lang.String"));
        Local object = local("object", RefType.v("java.lang.Object")), character = local("character", CharType.v());
        Unit identity = Jimple.v().newIdentityStmt(parameter, Jimple.v().newParameterRef(IntType.v(), 0));
        Unit assignment = Jimple.v().newAssignStmt(string, NullConstant.v()), end = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), identity, assignment, Jimple.v().newAssignStmt(object, NullConstant.v()), Jimple.v().newAssignStmt(character, IntConstant.v(65)), end);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        assertTrue(available.at(identity).isEmpty()); assertEquals(Collections.singletonList("i0"), names(available.at(assignment)));
        assertEquals(Arrays.asList("i0", "text"), names(available.at(end)));
        assertNotNull(available.resolve(end, new CustomLogPlan.Variable("text", "java.lang.String")));
        assertNull(available.resolve(end, new CustomLogPlan.Variable("text", "int")));
    }

    @Test public void aDefinitionOnOnlyOneBranchIsUnavailableAtTheJoin() {
        Local condition = local("flag", IntType.v()), value = local("value", IntType.v());
        Unit end = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), Jimple.v().newIdentityStmt(condition, Jimple.v().newParameterRef(IntType.v(), 0)),
                Jimple.v().newIfStmt(Jimple.v().newEqExpr(condition, IntConstant.v(0)), end),
                Jimple.v().newAssignStmt(value, IntConstant.v(9)), end);
        assertEquals(Collections.singletonList("flag"), names(new LocalVariableAvailability(body).at(end)));
    }

    @Test public void aLoopBackEdgeCannotInitializeTheFirstVisit() {
        Local value = local("value", IntType.v()); Unit entry = Jimple.v().newNopStmt();
        Collections.addAll(body.getUnits(), entry, Jimple.v().newAssignStmt(value, IntConstant.v(9)), Jimple.v().newGotoStmt(entry));
        assertTrue(new LocalVariableAvailability(body).at(entry).isEmpty());
    }

    @Test public void unreachableDefinitionsDoNotMakeVariablesAvailable() {
        Local value = local("unreachable", IntType.v());
        Unit end = Jimple.v().newReturnVoidStmt();
        Unit skipped = Jimple.v().newAssignStmt(value, IntConstant.v(9));
        Collections.addAll(body.getUnits(), Jimple.v().newGotoStmt(end), skipped, end);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        assertTrue(available.at(skipped).isEmpty());
        assertTrue(available.at(end).isEmpty());
        assertNull(available.resolve(end, new CustomLogPlan.Variable("unreachable", "int")));
    }

    @Test public void availableLocalsRemainSortedWhenDeclarationAndDefinitionOrderDiffer() {
        Local zeta = local("zeta", IntType.v()), alpha = local("alpha", IntType.v()), middle = local("middle", IntType.v());
        Unit first = Jimple.v().newAssignStmt(middle, IntConstant.v(1));
        Unit second = Jimple.v().newAssignStmt(zeta, IntConstant.v(2));
        Unit third = Jimple.v().newAssignStmt(alpha, IntConstant.v(3));
        Unit end = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), first, second, third, end);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        assertEquals(Collections.singletonList("middle"), names(available.at(second)));
        assertEquals(Arrays.asList("middle", "zeta"), names(available.at(third)));
        assertEquals(Arrays.asList("alpha", "middle", "zeta"), names(available.at(end)));
        assertSame(alpha, available.resolve(end, new CustomLogPlan.Variable("alpha", "int")));
    }

    @Test public void compactMasksUseTheSortedTableAcrossByteBoundaries() {
        Local[] values = new Local[11];
        for (int i = values.length - 1; i >= 0; i--) values[i] = local(String.format(Locale.ROOT, "v%02d", i), IntType.v());
        Unit first = Jimple.v().newAssignStmt(values[10], IntConstant.v(10));
        Unit second = Jimple.v().newAssignStmt(values[0], IntConstant.v(0));
        Unit end = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), first, second, end);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        List<CustomLogPlan.Variable> table = available.locals();
        assertEquals("v00", table.get(0).name);
        assertEquals("v10", table.get(10).name);
        assertEquals("", available.maskAt(first));
        assertEquals("", available.maskAt(Jimple.v().newNopStmt()));
        assertArrayEquals(new byte[] {0, 4}, Base64.getDecoder().decode(available.maskAt(second)));
        for (Unit unit : body.getUnits()) {
            BitSet mask = BitSet.valueOf(Base64.getDecoder().decode(available.maskAt(unit)));
            List<CustomLogPlan.Variable> restored = new ArrayList<>();
            for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) restored.add(table.get(i));
            assertEquals(names(available.at(unit)), names(restored));
        }
    }

    @Test public void handlerCannotReadTheResultOfAnAssignmentThatMayHaveThrown() {
        Local previous = local("previous", IntType.v()), result = local("result", IntType.v()), exception = local("exception", RefType.v("java.lang.Throwable"));
        SootClass throwable = new SootClass("java.lang.Throwable", Modifier.PUBLIC); Scene.v().addClass(throwable);
        Unit divide = Jimple.v().newAssignStmt(result, Jimple.v().newDivExpr(IntConstant.v(4), IntConstant.v(0)));
        Unit normalReturn = Jimple.v().newReturnVoidStmt();
        Unit handler = Jimple.v().newIdentityStmt(exception, Jimple.v().newCaughtExceptionRef()), handlerReturn = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), Jimple.v().newAssignStmt(previous, IntConstant.v(3)), divide, normalReturn, handler, handlerReturn);
        body.getTraps().add(Jimple.v().newTrap(throwable, divide, normalReturn, handler));
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        assertEquals(Collections.singletonList("previous"), names(available.at(handlerReturn)));
        assertEquals(Arrays.asList("previous", "result"), names(available.at(normalReturn)));
    }
    @Test public void verifierUninitializedStringAllocationsAndTheirAliasesAreNotOffered() {
        Local allocated = local("allocated", RefType.v("java.lang.String")), alias = local("alias", RefType.v("java.lang.String"));
        Unit allocation = Jimple.v().newAssignStmt(allocated, Jimple.v().newNewExpr(RefType.v("java.lang.String")));
        Unit copy = Jimple.v().newAssignStmt(alias, allocated), end = Jimple.v().newReturnVoidStmt();
        Collections.addAll(body.getUnits(), allocation, copy, end);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        assertTrue(available.at(copy).isEmpty()); assertTrue(available.at(end).isEmpty());
    }
    private Local local(String name, Type type) { Local local = Jimple.v().newLocal(name, type); body.getLocals().add(local); return local; }
    private static List<String> names(List<CustomLogPlan.Variable> variables) { List<String> names = new ArrayList<>(); for (CustomLogPlan.Variable variable : variables) names.add(variable.name); return names; }
}
