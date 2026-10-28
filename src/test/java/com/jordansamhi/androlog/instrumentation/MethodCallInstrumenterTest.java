package com.jordansamhi.androlog.instrumentation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import soot.Body;
import soot.G;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.Modifier;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.VoidType;
import soot.jimple.GotoStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.options.Options;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class MethodCallInstrumenterTest {
    private SootClass object;
    private SootClass application;
    private SootClass helper;
    private MethodCallInstrumenter instrumenter;

    @Before public void setupScene() throws Exception {
        G.reset();
        Options.v().set_allow_phantom_refs(true);
        object = addClass("java.lang.Object", null);
        addClass("java.lang.String", object);
        addClass("java.lang.Throwable", object);
        application = addClass("example.Application", object);
        application.setApplicationClass();
        helper = addClass(MethodCallInstrumenter.RUNTIME_CLASS, object);
        helper.setLibraryClass();
        instrumenter = new MethodCallInstrumenter("TAG", true, null);
        setHook("before", addMethod(helper, "before", Arrays.asList(string(), string(), IntType.v(), string(), string(), object.getType()), LongType.v()));
        setHook("enter", addMethod(helper, "enter", Arrays.asList(string(), object.getType()), VoidType.v()));
        setHook("after", addMethod(helper, "after", Collections.singletonList(LongType.v()), VoidType.v()));
        setHook("caught", addMethod(helper, "caught", Collections.emptyList(), VoidType.v()));
    }

    @After public void resetScene() { G.reset(); }

    @Test public void preservesOriginalUnitIdsAndIgnoresCoverageCallsAddedAfterCapture() {
        SootMethod target = addMethod(application, "target", Collections.emptyList(), VoidType.v());
        JimpleBody body = body("caller");
        Unit originalEntry = Jimple.v().newNopStmt();
        Unit originalCall = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(target.makeRef()));
        GotoStmt loop = Jimple.v().newGotoStmt(originalEntry);
        body.getUnits().add(originalEntry);
        body.getUnits().add(originalCall);
        body.getUnits().add(loop);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        instrumenter.capture(body);

        SootMethod coverage = addMethod(helper, "coverage", Collections.emptyList(), VoidType.v());
        Unit coverageCall = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(coverage.makeRef()));
        body.getUnits().insertBefore(coverageCall, originalCall);
        instrumenter.instrument(body);

        body.validate();
        List<InvokeExpr> calls = callsTo(body, "before");
        assertEquals(1, calls.size());
        assertEquals(2, ((IntConstant) calls.get(0).getArg(2)).value);
        assertEquals(target.getSignature(), ((StringConstant) calls.get(0).getArg(3)).value);
        assertEquals("static", ((StringConstant) calls.get(0).getArg(4)).value);
        assertEquals(1, callsTo(body, "after").size());
        assertEquals(1, callsTo(body, "enter").size());
        assertSame(originalEntry, loop.getTarget());
        assertTrue(body.getUnits().contains(coverageCall));
    }

    @Test public void branchesToOriginalInvocationsExecuteBeforeHookAndHandlersKeepIdentityFirst() {
        SootMethod target = addMethod(application, "target", Collections.emptyList(), VoidType.v());
        JimpleBody body = body("withHandler");
        Unit invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(target.makeRef()));
        GotoStmt branch = Jimple.v().newGotoStmt(invocation);
        Local exception = Jimple.v().newLocal("caught", RefType.v("java.lang.Throwable"));
        body.getLocals().add(exception);
        IdentityStmt handler = Jimple.v().newIdentityStmt(exception, Jimple.v().newCaughtExceptionRef());
        Unit returned = Jimple.v().newReturnVoidStmt();
        body.getUnits().add(branch);
        body.getUnits().add(invocation);
        body.getUnits().add(returned);
        body.getUnits().add(handler);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        body.getTraps().add(Jimple.v().newTrap(Scene.v().getSootClass("java.lang.Throwable"), invocation, returned, handler));
        instrumenter.capture(body);
        instrumenter.instrument(body);

        assertEquals("before", ((Stmt) branch.getTarget()).getInvokeExpr().getMethodRef().name());
        assertSame(handler, body.getTraps().getFirst().getHandlerUnit());
        assertEquals("caught", ((Stmt) body.getUnits().getSuccOf(handler)).getInvokeExpr().getMethodRef().name());
        body.validate();
    }

    @Test public void constructorCallsUseNullInsteadOfUninitializedReceiver() {
        SootMethod constructor = new SootMethod("<init>", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC);
        application.addMethod(constructor);
        JimpleBody body = body("construct");
        Local allocated = Jimple.v().newLocal("newObject", application.getType());
        body.getLocals().add(allocated);
        body.getUnits().add(Jimple.v().newAssignStmt(allocated, Jimple.v().newNewExpr(application.getType())));
        body.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(allocated, constructor.makeRef())));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        instrumenter.capture(body);
        instrumenter.instrument(body);

        InvokeExpr before = callsTo(body, "before").get(0);
        assertEquals("constructor", ((StringConstant) before.getArg(4)).value);
        assertTrue(before.getArg(5) instanceof NullConstant);
        body.validate();
    }

    @Test public void statementProbesAndCallHooksPreserveIdsAndLoopEntrySemantics() {
        SootClass logger = addClass(com.jordansamhi.androspecter.utils.Constants.LOG_CHECKER_CLASS, object);
        addMethod(logger, "log", Arrays.asList(string(), string()), VoidType.v());
        SootMethod target = addMethod(application, "target", Collections.emptyList(), VoidType.v());
        JimpleBody body = body("combined");
        Unit invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(target.makeRef()));
        GotoStmt loop = Jimple.v().newGotoStmt(invocation);
        body.getUnits().add(invocation);
        body.getUnits().add(loop);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        String message = "STATEMENT=" + body.getMethod().getSignature() + "|" + invocation + "|1";

        instrumenter.capture(body);
        new StatementInstrumenter("TAG", true, "example").instrument(body);
        instrumenter.instrument(body);

        assertEquals(1, callsTo(body, "before").size());
        assertEquals(1, ((IntConstant) callsTo(body, "before").get(0).getArg(2)).value);
        InvokeExpr destination = ((Stmt) loop.getTarget()).getInvokeExpr();
        assertEquals("log", destination.getMethodRef().name());
        assertEquals(message, ((StringConstant) destination.getArg(0)).value);
        assertEquals("enter", ((Stmt) body.getUnits().getFirst()).getInvokeExpr().getMethodRef().name());
        assertNotSame(body.getUnits().getFirst(), loop.getTarget());
        assertEquals(2, callsTo(body, "log").size());
        body.validate();
    }

    @Test public void packageScopeExcludesAdjacentNamesAndKeepsSubpackages() throws Exception {
        for (String packageName : Arrays.asList("example", "example.nested", "exampleother")) {
            application = addClass(packageName + ".CallScopeFixture", object);
            application.setApplicationClass();
            SootMethod target = addMethod(application, "target", Collections.emptyList(), VoidType.v());
            JimpleBody body = body("caller");
            body.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(target.makeRef())));
            body.getUnits().add(Jimple.v().newReturnVoidStmt());
            MethodCallInstrumenter scoped = new MethodCallInstrumenter("TAG", true, "example.");
            for (String name : Arrays.asList("before", "enter", "after", "caught")) {
                Field field = MethodCallInstrumenter.class.getDeclaredField(name);
                field.setAccessible(true); field.set(scoped, field.get(instrumenter));
            }
            scoped.capture(body); scoped.instrument(body);
            assertEquals(packageName, "exampleother".equals(packageName) ? 0 : 1, callsTo(body, "before").size());
            assertEquals(packageName, "exampleother".equals(packageName) ? 0 : 1, callsTo(body, "enter").size());
        }
    }

    private JimpleBody body(String name) {
        SootMethod method = addMethod(application, name, Collections.emptyList(), VoidType.v());
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        return body;
    }

    private void setHook(String name, SootMethod method) throws Exception {
        Field field = MethodCallInstrumenter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instrumenter, method);
    }

    private static SootClass addClass(String name, SootClass superclass) {
        SootClass type = new SootClass(name, Modifier.PUBLIC);
        if (superclass != null) type.setSuperclass(superclass);
        Scene.v().addClass(type);
        return type;
    }

    private static SootMethod addMethod(SootClass owner, String name, List<Type> parameters, Type result) {
        SootMethod method = new SootMethod(name, parameters, result, Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        return method;
    }

    private static RefType string() { return RefType.v("java.lang.String"); }

    private static List<InvokeExpr> callsTo(Body body, String name) {
        List<InvokeExpr> result = new ArrayList<>();
        for (Unit unit : body.getUnits()) {
            Stmt statement = (Stmt) unit;
            if (statement.containsInvokeExpr() && statement.getInvokeExpr().getMethodRef().name().equals(name)) {
                result.add(statement.getInvokeExpr());
            }
        }
        return result;
    }
}
