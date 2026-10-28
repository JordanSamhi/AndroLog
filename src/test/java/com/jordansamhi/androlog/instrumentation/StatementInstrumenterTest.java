package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androspecter.utils.Constants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import soot.*;
import soot.baf.BafASMBackend;
import soot.jimple.*;
import soot.options.Options;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.Assert.*;

/** Execute transformed bytecode to check coverage and application behavior together. */
public class StatementInstrumenterTest {
    private static final List<String> observations = new ArrayList<>();
    private SootClass object;
    private SootClass application;
    private SootClass logger;
    private StatementInstrumenter instrumenter;

    public static void record(String message, String tag) {
        assertEquals("TEST", tag);
        observations.add(message);
    }

    @Before public void setupScene() {
        G.reset();
        observations.clear();
        Options.v().set_allow_phantom_refs(true);
        // These fixtures define only the types they use. Skip optional Baf optimizations, which
        // otherwise require the full JDK exception hierarchy; emit and verify the bytecode as-is.
        Options.v().setPhaseOption("bb", "enabled:false");
        object = addClass("java.lang.Object", null);
        addClass("java.lang.String", object);
        SootClass throwable = addClass("java.lang.Throwable", object);
        SootClass exception = addClass("java.lang.Exception", throwable);
        SootClass runtime = addClass("java.lang.RuntimeException", exception);
        addClass("java.lang.ArithmeticException", runtime);
        object.addMethod(new SootMethod("<init>", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC));
        application = addClass("example.StatementFixture", object);
        application.setApplicationClass();
        logger = addClass(Constants.LOG_CHECKER_CLASS, object);
        SootClass recorder = addClass(getClass().getName(), object);
        SootMethod record = addMethod(recorder, "record", Arrays.asList(string(), string()), VoidType.v());
        SootMethod log = addMethod(logger, "log", Arrays.asList(string(), string()), VoidType.v());
        JimpleBody logBody = Jimple.v().newBody(log);
        log.setActiveBody(logBody);
        Local message = parameter(logBody, "message", string(), 0);
        Local tag = parameter(logBody, "tag", string(), 1);
        logBody.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(record.makeRef(), message, tag)));
        logBody.getUnits().add(Jimple.v().newReturnVoidStmt());
        instrumenter = new StatementInstrumenter("TEST", true, "example");
    }

    @After public void resetScene() { G.reset(); observations.clear(); }

    @Test public void takenAndFallthroughBranchesAndJumpsRecordTheirOriginalStatements() throws Exception {
        JimpleBody body = body("branch", Collections.singletonList(IntType.v()), IntType.v());
        Local flag = parameter(body, "flag", IntType.v(), 0);
        Local result = local(body, "result", IntType.v());
        Unit negative = Jimple.v().newAssignStmt(result, IntConstant.v(-1));
        Unit returned = Jimple.v().newReturnStmt(result);
        Unit condition = Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), negative);
        Unit positive = Jimple.v().newAssignStmt(result, IntConstant.v(1));
        Unit jump = Jimple.v().newGotoStmt(returned);
        Collections.addAll(body.getUnits(), condition, positive, jump, negative, returned);
        Map<Unit, String> expected = messages(body);
        instrumenter.instrument(body);
        Class<?> fixture = loadFixture();

        assertEquals(-1, fixture.getMethod("branch", int.class).invoke(null, 0));
        assertEquals(Arrays.asList(expected.get(condition), expected.get(negative)), observations);
        observations.clear();
        assertEquals(1, fixture.getMethod("branch", int.class).invoke(null, 1));
        assertEquals(Arrays.asList(expected.get(condition), expected.get(positive), expected.get(jump)), observations);
    }

    @Test public void lookupAndTableSwitchesRecordTheirSelectedTargets() throws Exception {
        for (boolean table : new boolean[]{false, true}) {
            String name = table ? "tableSwitch" : "lookupSwitch";
            JimpleBody body = body(name, Collections.singletonList(IntType.v()), IntType.v());
            Local key = parameter(body, "key", IntType.v(), 0);
            Local result = local(body, "result", IntType.v());
            Unit first = Jimple.v().newAssignStmt(result, IntConstant.v(10));
            Unit second = Jimple.v().newAssignStmt(result, IntConstant.v(20));
            Unit fallback = Jimple.v().newAssignStmt(result, IntConstant.v(30));
            Unit returned = Jimple.v().newReturnStmt(result);
            Unit select = table
                    ? Jimple.v().newTableSwitchStmt(key, 1, 2, Arrays.asList(first, second), fallback)
                    : Jimple.v().newLookupSwitchStmt(key, Arrays.asList(IntConstant.v(1), IntConstant.v(7)), Arrays.asList(first, second), fallback);
            Unit firstJump = Jimple.v().newGotoStmt(returned), secondJump = Jimple.v().newGotoStmt(returned);
            Collections.addAll(body.getUnits(), select, first, firstJump, second, secondJump, fallback, returned);
            Map<Unit, String> expected = messages(body);
            instrumenter.instrument(body);
            Class<?> fixture = loadFixture();
            observations.clear();
            assertEquals(10, fixture.getMethod(name, int.class).invoke(null, 1));
            assertEquals(Arrays.asList(expected.get(select), expected.get(first), expected.get(firstJump)), observations);
            observations.clear();
            assertEquals(20, fixture.getMethod(name, int.class).invoke(null, table ? 2 : 7));
            assertEquals(Arrays.asList(expected.get(select), expected.get(second), expected.get(secondJump)), observations);
            observations.clear();
            assertEquals(30, fixture.getMethod(name, int.class).invoke(null, 99));
            assertEquals(Arrays.asList(expected.get(select), expected.get(fallback)), observations);
        }
    }

    @Test public void loopBackEdgesPassThroughTheDestinationProbe() throws Exception {
        JimpleBody body = body("loop", Collections.singletonList(IntType.v()), IntType.v());
        Local limit = parameter(body, "limit", IntType.v(), 0);
        Local count = local(body, "count", IntType.v());
        Unit initialize = Jimple.v().newAssignStmt(count, IntConstant.v(0));
        Unit increment = Jimple.v().newAssignStmt(count, Jimple.v().newAddExpr(count, IntConstant.v(1)));
        Unit again = Jimple.v().newIfStmt(Jimple.v().newLtExpr(count, limit), increment);
        Collections.addAll(body.getUnits(), initialize, increment, again, Jimple.v().newReturnStmt(count));
        Map<Unit, String> expected = messages(body);
        instrumenter.instrument(body);
        assertEquals(3, loadFixture().getMethod("loop", int.class).invoke(null, 3));
        assertEquals(Arrays.asList(expected.get(initialize), expected.get(increment), expected.get(again),
                expected.get(increment), expected.get(again), expected.get(increment), expected.get(again)), observations);
    }

    @Test public void throwingOperationsAreRecordedAndCaughtExceptionsKeepTheirHandler() throws Exception {
        JimpleBody body = body("divide", Collections.singletonList(IntType.v()), IntType.v());
        Local divisor = parameter(body, "divisor", IntType.v(), 0);
        Local result = local(body, "result", IntType.v());
        Local caught = local(body, "caught", RefType.v("java.lang.ArithmeticException"));
        Unit division = Jimple.v().newAssignStmt(result, Jimple.v().newDivExpr(IntConstant.v(10), divisor));
        Unit returned = Jimple.v().newReturnStmt(result);
        Unit handler = Jimple.v().newIdentityStmt(caught, Jimple.v().newCaughtExceptionRef());
        Unit fallback = Jimple.v().newAssignStmt(result, IntConstant.v(-1));
        Collections.addAll(body.getUnits(), division, returned, handler, fallback, Jimple.v().newReturnStmt(result));
        Trap trap = Jimple.v().newTrap(Scene.v().getSootClass("java.lang.ArithmeticException"), division, returned, handler);
        body.getTraps().add(trap);
        Map<Unit, String> expected = messages(body);
        instrumenter.instrument(body);
        assertSame(handler, trap.getHandlerUnit());
        assertSame(returned, trap.getEndUnit());
        assertSame(division, body.getUnits().getSuccOf(trap.getBeginUnit()));
        Class<?> fixture = loadFixture();

        assertEquals(-1, fixture.getMethod("divide", int.class).invoke(null, 0));
        assertEquals(Arrays.asList(expected.get(division), expected.get(fallback)), observations);
        observations.clear();
        assertEquals(5, fixture.getMethod("divide", int.class).invoke(null, 2));
        assertEquals(Collections.singletonList(expected.get(division)), observations);
    }

    @Test public void explicitThrowIsRecordedWithoutChangingTheException() throws Exception {
        JimpleBody body = body("throwGiven", Collections.singletonList(RefType.v("java.lang.Throwable")), VoidType.v());
        Local error = parameter(body, "error", RefType.v("java.lang.Throwable"), 0);
        Unit thrown = Jimple.v().newThrowStmt(error);
        body.getUnits().add(thrown);
        String expected = messages(body).get(thrown);
        instrumenter.instrument(body);
        Class<?> fixture = loadFixture();
        Exception original = new Exception("expected");
        try {
            fixture.getMethod("throwGiven", Throwable.class).invoke(null, original);
            fail("Expected original exception");
        } catch (InvocationTargetException failure) {
            assertSame(original, failure.getCause());
        }
        assertEquals(Collections.singletonList(expected), observations);
    }

    @Test public void constructorProbesDoNotReadUninitializedThis() throws Exception {
        SootMethod constructor = new SootMethod("<init>", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC);
        application.addMethod(constructor);
        JimpleBody body = Jimple.v().newBody(constructor);
        constructor.setActiveBody(body);
        Local receiver = local(body, "this", application.getType());
        body.getUnits().add(Jimple.v().newIdentityStmt(receiver, Jimple.v().newThisRef(application.getType())));
        Unit superCall = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(receiver, object.getMethod("void <init>()").makeRef()));
        body.getUnits().add(superCall);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        String expected = messages(body).get(superCall);
        instrumenter.instrument(body);
        assertNotNull(loadFixture().getConstructor().newInstance());
        assertEquals(Collections.singletonList(expected), observations);
    }

    @Test public void monitorAndIdentityAndReturnStatementsRemainExcluded() throws Exception {
        JimpleBody body = body("synchronizedBlock", Collections.singletonList(object.getType()), VoidType.v());
        Local lock = parameter(body, "lock", object.getType(), 0);
        Unit marker = Jimple.v().newNopStmt();
        Collections.addAll(body.getUnits(), Jimple.v().newEnterMonitorStmt(lock), marker,
                Jimple.v().newExitMonitorStmt(lock), Jimple.v().newReturnVoidStmt());
        String expected = messages(body).get(marker);
        instrumenter.instrument(body);
        loadFixture().getMethod("synchronizedBlock", Object.class).invoke(null, new Object());
        assertEquals(Collections.singletonList(expected), observations);
    }

    @Test public void packageScopeAndHelperClassesAreNotInstrumented() {
        JimpleBody body = body("outsideScope", Collections.emptyList(), VoidType.v());
        Collections.addAll(body.getUnits(), Jimple.v().newNopStmt(), Jimple.v().newReturnVoidStmt());
        new StatementInstrumenter("TEST", true, "other").instrument(body);
        assertEquals(2, body.getUnits().size());
        Body helper = logger.getMethod(Constants.SUB_SIG_LOG).getActiveBody();
        int originalSize = helper.getUnits().size();
        new StatementInstrumenter("TEST", true, null).instrument(helper);
        assertEquals(originalSize, helper.getUnits().size());
    }

    @Test public void packageScopeMatchesWholeSegmentsAndNormalizesATrailingDot() throws Exception {
        for (String packageName : Arrays.asList("example", "example.nested", "exampleother")) {
            application = addClass(packageName + ".PackageFixture", object);
            application.setApplicationClass();
            JimpleBody body = body("value", Collections.emptyList(), IntType.v());
            Local value = local(body, "value", IntType.v());
            Collections.addAll(body.getUnits(), Jimple.v().newAssignStmt(value, IntConstant.v(42)), Jimple.v().newReturnStmt(value));
            new StatementInstrumenter("TEST", true, "example.").instrument(body);
            observations.clear();
            assertEquals(42, loadFixture().getMethod("value").invoke(null));
            assertEquals(packageName, "exampleother".equals(packageName) ? 0 : 1, observations.size());
        }
    }

    private Map<Unit, String> messages(Body body) {
        Map<Unit, String> result = new IdentityHashMap<>();
        int index = 0;
        for (Unit unit : body.getUnits()) {
            index++;
            if (StatementInstrumenter.isEligible((Stmt) unit)) {
                result.put(unit, "STATEMENT=" + body.getMethod().getSignature() + "|" + unit + "|" + index);
            }
        }
        return result;
    }

    private Class<?> loadFixture() throws Exception {
        Map<String, byte[]> bytecode = new HashMap<>();
        for (SootClass type : Arrays.asList(application, logger)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new BafASMBackend(type, Options.java_version_8).generateClassFile(output);
            bytecode.put(type.getName(), output.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = bytecode.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(application.getName());
    }

    private JimpleBody body(String name, List<Type> parameters, Type result) {
        SootMethod method = addMethod(application, name, parameters, result);
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        return body;
    }

    private static Local local(Body body, String name, Type type) {
        Local local = Jimple.v().newLocal(name, type);
        body.getLocals().add(local);
        return local;
    }

    private static Local parameter(Body body, String name, Type type, int index) {
        Local local = local(body, name, type);
        body.getUnits().add(Jimple.v().newIdentityStmt(local, Jimple.v().newParameterRef(type, index)));
        return local;
    }

    private static SootClass addClass(String name, SootClass parent) {
        SootClass type = new SootClass(name, Modifier.PUBLIC);
        if (parent != null) type.setSuperclass(parent);
        Scene.v().addClass(type);
        return type;
    }

    private static SootMethod addMethod(SootClass owner, String name, List<Type> parameters, Type result) {
        SootMethod method = new SootMethod(name, parameters, result, Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        return method;
    }

    private static RefType string() { return RefType.v("java.lang.String"); }
}
