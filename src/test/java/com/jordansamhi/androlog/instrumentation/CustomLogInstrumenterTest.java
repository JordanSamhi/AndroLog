package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androlog.CustomLogPlan;
import com.jordansamhi.androspecter.utils.Constants;
import org.junit.*;
import soot.*;
import soot.baf.BafASMBackend;
import soot.jimple.*;
import soot.options.Options;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Execute transformed bytecode, including repeated visits and combination with bulk coverage. */
public class CustomLogInstrumenterTest {
    private static final List<String> messages = new ArrayList<>();
    private SootClass object, app, androidLog, coverageLog;
    public static void record(String message, String tag) { assertEquals("TEST", tag); messages.add(message); }

    @Before public void setup() {
        G.reset(); messages.clear(); Options.v().set_allow_phantom_refs(true); Options.v().setPhaseOption("bb", "enabled:false");
        object = type("java.lang.Object", null); type("java.lang.String", object);
        app = type("example.CustomFixture", object); app.setApplicationClass();
        SootClass recorder = type(getClass().getName(), object);
        SootMethod record = method(recorder, "record", Arrays.asList(string(), string()), VoidType.v());
        androidLog = type("android.util.Log", object);
        JimpleBody android = body(method(androidLog, "i", Arrays.asList(string(), string()), IntType.v()));
        Local tag = parameter(android, "tag", string(), 0), message = parameter(android, "message", string(), 1);
        android.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(record.makeRef(), message, tag)));
        android.getUnits().add(Jimple.v().newReturnStmt(IntConstant.v(0)));
        coverageLog = type(Constants.LOG_CHECKER_CLASS, object);
        JimpleBody coverage = body(method(coverageLog, "log", Arrays.asList(string(), string()), VoidType.v()));
        Local coverageMessage = parameter(coverage, "message", string(), 0), coverageTag = parameter(coverage, "tag", string(), 1);
        coverage.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(record.makeRef(), coverageMessage, coverageTag)));
        coverage.getUnits().add(Jimple.v().newReturnVoidStmt());
    }
    @After public void cleanup() { G.reset(); messages.clear(); }

    @Test public void selectedLoopPointLogsEveryVisitAndPreservesOriginalCoverageIndices() throws Exception {
        JimpleBody body = body(method(app, "count", Collections.singletonList(IntType.v()), IntType.v()));
        Local limit = parameter(body, "limit", IntType.v(), 0), count = local(body, "count", IntType.v());
        Unit init = Jimple.v().newAssignStmt(count, IntConstant.v(0));
        Unit increment = Jimple.v().newAssignStmt(count, Jimple.v().newAddExpr(count, IntConstant.v(1)));
        Unit again = Jimple.v().newIfStmt(Jimple.v().newLtExpr(count, limit), increment);
        Collections.addAll(body.getUnits(), init, increment, again, Jimple.v().newReturnStmt(count));
        CustomLogInstrumenter custom = custom(body, 3, increment.toString(), "METHOD=literal, not coverage");
        custom.capture(body);
        String expectedCoverage = "STATEMENT=" + body.getMethod().getSignature() + "|" + increment + "|3";
        new StatementInstrumenter("TEST", true, null).instrument(body);
        custom.instrument(body); custom.verifyComplete();
        assertEquals(3, load().getMethod("count", int.class).invoke(null, 3));
        assertEquals(3, Collections.frequency(messages, "CUSTOM=METHOD=literal, not coverage"));
        assertEquals(3, Collections.frequency(messages, expectedCoverage));
        assertEquals(3, messages.stream().filter(value -> value.startsWith("CUSTOM=")).count());
    }

    @Test public void takenBranchRunsOnlyItsSelectedPoint() throws Exception {
        JimpleBody body = body(method(app, "branch", Collections.singletonList(IntType.v()), IntType.v()));
        Local flag = parameter(body, "flag", IntType.v(), 0);
        Unit chosen = Jimple.v().newReturnStmt(IntConstant.v(7));
        Unit condition = Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), chosen);
        Collections.addAll(body.getUnits(), condition, Jimple.v().newReturnStmt(IntConstant.v(9)), chosen);
        CustomLogInstrumenter custom = custom(body, 4, chosen.toString(), "reached this point");
        custom.capture(body); custom.instrument(body); custom.verifyComplete();
        Class<?> loaded = load();
        assertEquals(9, loaded.getMethod("branch", int.class).invoke(null, 1)); assertTrue(messages.isEmpty());
        assertEquals(7, loaded.getMethod("branch", int.class).invoke(null, 0));
        assertEquals(Collections.singletonList("CUSTOM=reached this point"), messages);
    }

    @Test public void trackedPointEmitsUniqueMarkerOnEachVisit() throws Exception {
        JimpleBody body = body(method(app, "tracked", Collections.emptyList(), VoidType.v()));
        Unit target = Jimple.v().newReturnVoidStmt(); body.getUnits().add(target);
        CustomLogPlan.Point point = new CustomLogPlan.Point(body.getMethod().getSignature(), 1, target.toString(), "same message");
        point.id = String.join("", Collections.nCopies(32, "a"));
        CustomLogInstrumenter custom = new CustomLogInstrumenter("TEST", new CustomLogPlan(String.join("", Collections.nCopies(64, "b")), Collections.singletonList(point)));
        custom.capture(body); custom.instrument(body); custom.verifyComplete();
        load().getMethod("tracked").invoke(null);
        assertEquals(Arrays.asList("CUSTOM=same message", "CUSTOM_POINT=" + point.id), messages);
    }

    @Test public void staleOrUnsafePointsAndMissingMethodsNeverSilentlySucceed() throws Exception {
        JimpleBody body = body(method(app, "unchanged", Collections.singletonList(IntType.v()), VoidType.v()));
        parameter(body, "value", IntType.v(), 0); body.getUnits().add(Jimple.v().newReturnVoidStmt());
        for (CustomLogInstrumenter custom : Arrays.asList(custom(body, 99, "return", "test"), custom(body, 2, "wrong statement", "test"),
                custom(body, 1, body.getUnits().getFirst().toString(), "test"))) {
            try { custom.capture(body); fail("Expected stale or unsafe point failure"); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("no longer matches")); }
        }
        CustomLogInstrumenter missing = custom(body, 2, "return", "test");
        try { missing.verifyComplete(); fail("Expected missing point failure"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("not found")); }
    }

    @Test public void generatedBytecodeLogsTypedLocalValuesAndRepeatedPlaceholders() throws Exception {
        runtimeSignatures();
        List<Type> types = Arrays.asList(IntType.v(), BooleanType.v(), string(), LongType.v(), FloatType.v(), DoubleType.v(), ByteType.v(), ShortType.v());
        JimpleBody body = body(method(app, "values", types, VoidType.v()));
        List<CustomLogPlan.Variable> variables = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            parameter(body, "v" + i, types.get(i), i); variables.add(new CustomLogPlan.Variable("v" + i, types.get(i).toString()));
        }
        Unit end = Jimple.v().newReturnVoidStmt(); body.getUnits().add(end);
        CustomLogPlan.Point point = new CustomLogPlan.Point(body.getMethod().getSignature(), 9, end.toString(),
                "{v0}|{v1}|{v2}|{v3}|{v4}|{v5}|{v6}|{v7}|again={v0}");
        point.variables = variables;
        CustomLogInstrumenter custom = new CustomLogInstrumenter("TEST", new CustomLogPlan(String.join("", Collections.nCopies(64, "a")), Collections.singletonList(point)));
        custom.capture(body); custom.instrument(body); custom.verifyComplete();
        Set<String> valueSignatures = new HashSet<>();
        for (Unit unit : body.getUnits()) if (unit instanceof Stmt && ((Stmt) unit).containsInvokeExpr()) {
            SootMethodRef reference = ((Stmt) unit).getInvokeExpr().getMethodRef();
            if (reference.declaringClass().getName().equals(CustomLogInstrumenter.RUNTIME_CLASS) && reference.name().equals("value")) {
                valueSignatures.add(reference.getSubSignature().toString());
            }
        }
        assertTrue(valueSignatures.contains("void value(java.lang.StringBuilder,byte)"));
        assertTrue(valueSignatures.contains("void value(java.lang.StringBuilder,short)"));
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            load().getMethod("values", int.class, boolean.class, String.class, long.class, float.class, double.class, byte.class, short.class)
                    .invoke(null, 42, true, "line\nnext", 1234567890123L, 1.5f, 2.25d, (byte) -7, (short) 300);
        } finally { System.setOut(previous); }
        assertTrue(output.toString("UTF-8").contains("CUSTOM_VALUE=" + point.id + "|42|true|line\\nnext|1234567890123|1.5|2.25|-7|300|again=42"));
        assertTrue(output.toString("UTF-8").contains("CUSTOM_POINT=" + point.id));
    }

    @Test public void variablesMustBeInitializedBeforeTheSelectedStatement() throws Exception {
        JimpleBody body = body(method(app, "tooEarly", Collections.emptyList(), VoidType.v()));
        Local value = local(body, "value", IntType.v());
        Unit assignment = Jimple.v().newAssignStmt(value, IntConstant.v(42));
        Collections.addAll(body.getUnits(), assignment, Jimple.v().newReturnVoidStmt());
        CustomLogPlan.Point point = new CustomLogPlan.Point(body.getMethod().getSignature(), 1, assignment.toString(), "{value}");
        point.variables.add(new CustomLogPlan.Variable("value", "int"));
        CustomLogInstrumenter custom = new CustomLogInstrumenter("TEST", new CustomLogPlan(String.join("", Collections.nCopies(64, "a")), Collections.singletonList(point)));
        try { custom.capture(body); fail("Expected uninitialized local rejection"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("not initialized")); }
    }

    @Test public void bracesRemainLiteralWithoutAnExplicitVariableSelection() throws Exception {
        JimpleBody body = body(method(app, "literalBraces", Collections.emptyList(), VoidType.v()));
        Unit end = Jimple.v().newReturnVoidStmt(); body.getUnits().add(end);
        CustomLogInstrumenter custom = custom(body, 1, end.toString(), "value={value}");
        custom.capture(body); custom.instrument(body); load().getMethod("literalBraces").invoke(null);
        assertEquals(Collections.singletonList("CUSTOM=value={value}"), messages);
    }

    private void runtimeSignatures() {
        SootClass builder = type("java.lang.StringBuilder", object);
        SootClass runtime = type(CustomLogInstrumenter.RUNTIME_CLASS, object);
        runtime.setResolvingLevel(SootClass.SIGNATURES); builder.setResolvingLevel(SootClass.SIGNATURES);
        method(runtime, "create", Collections.emptyList(), builder.getType());
        method(runtime, "text", Arrays.asList(builder.getType(), string()), VoidType.v());
        for (Type type : Arrays.asList(ByteType.v(), ShortType.v(), IntType.v(), BooleanType.v(), string(), LongType.v(), FloatType.v(), DoubleType.v())) {
            method(runtime, "value", Arrays.asList(builder.getType(), type), VoidType.v());
        }
        method(runtime, "write", Arrays.asList(string(), string(), string(), builder.getType()), VoidType.v());
    }

    private CustomLogInstrumenter custom(Body body, int index, String statement, String message) throws IOException {
        return new CustomLogInstrumenter("TEST", new CustomLogPlan(String.join("", Collections.nCopies(64, "a")), Collections.singletonList(
                new CustomLogPlan.Point(body.getMethod().getSignature(), index, statement, message))));
    }
    private Class<?> load() throws Exception {
        Map<String, byte[]> bytes = new HashMap<>();
        for (SootClass type : Arrays.asList(app, androidLog, coverageLog)) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream(); new BafASMBackend(type, Options.java_version_8).generateClassFile(stream); bytes.put(type.getName(), stream.toByteArray());
        }
        return new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] code = bytes.get(name); if (code == null) throw new ClassNotFoundException(name); return defineClass(name, code, 0, code.length);
            }
        }.loadClass(app.getName());
    }
    private static SootClass type(String name, SootClass parent) { SootClass type = new SootClass(name, Modifier.PUBLIC); if (parent != null) type.setSuperclass(parent); Scene.v().addClass(type); return type; }
    private static SootMethod method(SootClass owner, String name, List<Type> parameters, Type result) { SootMethod method = new SootMethod(name, parameters, result, Modifier.PUBLIC | Modifier.STATIC); owner.addMethod(method); return method; }
    private static JimpleBody body(SootMethod method) { JimpleBody body = Jimple.v().newBody(method); method.setActiveBody(body); return body; }
    private static Local local(Body body, String name, Type type) { Local value = Jimple.v().newLocal(name, type); body.getLocals().add(value); return value; }
    private static Local parameter(Body body, String name, Type type, int index) { Local value = local(body, name, type); body.getUnits().add(Jimple.v().newIdentityStmt(value, Jimple.v().newParameterRef(type, index))); return value; }
    private static RefType string() { return RefType.v("java.lang.String"); }
}
