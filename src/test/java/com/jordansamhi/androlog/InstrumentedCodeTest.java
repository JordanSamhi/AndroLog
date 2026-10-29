package com.jordansamhi.androlog;

import com.jordansamhi.androlog.instrumentation.MethodCallInstrumenter;
import com.jordansamhi.androspecter.utils.Constants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import soot.*;
import soot.jimple.*;
import soot.options.Options;

import java.util.*;

import static org.junit.Assert.*;

public class InstrumentedCodeTest {
    private SootClass object, application, logger, runtime, androidLog;
    private SootMethod log, custom, before, enter, after, caught;

    @Before public void setup() {
        G.reset(); Options.v().set_allow_phantom_refs(true);
        object = type("java.lang.Object", null); type("java.lang.String", object);
        application = type("example.App", object); application.setApplicationClass();
        logger = type(Constants.LOG_CHECKER_CLASS, object);
        runtime = type(MethodCallInstrumenter.RUNTIME_CLASS, object);
        androidLog = type("android.util.Log", object);
        log = method(logger, "log", Arrays.asList(string(), string()), VoidType.v());
        custom = method(androidLog, "i", Arrays.asList(string(), string()), IntType.v());
        before = method(runtime, "before", Arrays.asList(string(), string(), IntType.v(), string(), string(), object.getType()), LongType.v());
        enter = method(runtime, "enter", Arrays.asList(string(), object.getType()), VoidType.v());
        after = method(runtime, "after", Collections.singletonList(LongType.v()), VoidType.v());
        caught = method(runtime, "caught", Collections.emptyList(), VoidType.v());
    }
    @After public void reset() { G.reset(); }

    @Test public void readsAllExpectedCategoriesFromTheEmbeddedPayloadNotReloadedCoordinates() {
        JimpleBody body = body("run");
        String signature = body.getMethod().getSignature();
        Local value = local(body, "renamed", IntType.v());
        String payload = signature + "|oldName = 42|79";
        for (String category : Arrays.asList("CLASS", "METHOD", "ACTIVITY", "SERVICE", "BROADCASTRECEIVER", "CONTENTPROVIDER")) {
            String data = category.equals("METHOD") ? signature : application.getName();
            if (!category.equals("METHOD") && !category.equals("CLASS")) data += "|void lifecycle()";
            body.getUnits().add(probe(category + "=" + data));
        }
        body.getUnits().add(probe("STATEMENT=" + payload));
        body.getUnits().add(Jimple.v().newAssignStmt(value, IntConstant.v(42)));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result result = InstrumentedCode.inspect(body, "TEST", "Activity");
        assertTrue(result.foundProbes);
        assertEquals(7, result.expected.size());
        assertEquals(Collections.singleton(application.getName()), result.expected.get("activities"));
        assertEquals(Collections.singleton("STATEMENT=" + payload), result.expected.get("statements"));
        assertEquals(2, result.code.getStatements().size());
        CoverageMethod.Statement row = result.code.getStatements().get(0);
        assertEquals(1, row.getLine()); assertEquals("renamed = 42", row.getText());
        assertEquals(payload, row.getCoveragePayload()); assertTrue(row.isEligible());
        assertFalse(result.code.getStatements().get(1).isEligible());
    }

    @Test public void customAndAutomaticAndRuntimeProbesShareTheSameCleanedLocation() {
        JimpleBody body = body("mixed"); String signature = body.getMethod().getSignature();
        SootMethod target = method(application, "target", Collections.emptyList(), VoidType.v());
        Local token = local(body, "t", LongType.v());
        body.getUnits().add(invoke(enter, StringConstant.v(signature), NullConstant.v()));
        body.getUnits().add(probe("STATEMENT=" + signature + "|staticinvoke " + target.getSignature() + "()|44"));
        body.getUnits().add(Jimple.v().newAssignStmt(token, Jimple.v().newStaticInvokeExpr(before.makeRef(),
                StringConstant.v("TEST"), StringConstant.v(signature), IntConstant.v(44),
                StringConstant.v(target.getSignature()), StringConstant.v("static"), NullConstant.v())));
        body.getUnits().add(custom("CUSTOM=message"));
        body.getUnits().add(custom("CUSTOM_POINT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        body.getUnits().add(invoke(target)); body.getUnits().add(invoke(after, token));
        body.getUnits().add(invoke(caught)); body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result result = InstrumentedCode.inspect(body, "TEST", null);
        assertEquals(2, result.code.getStatements().size());
        assertEquals(1, result.customPoints.size());
        Map<String, Object> point = result.customPoints.get(0);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", point.get("id"));
        assertEquals("message", point.get("message")); assertEquals(1, point.get("index"));
        assertEquals(result.code.getStatements().get(0).getText(), point.get("statement"));
        assertEquals(44, result.callSites.get(0).line);
        assertEquals(Integer.valueOf(1), result.callSiteRows.get(signature + "|44"));
        assertTrue(result.graphMethods.contains(signature));
    }

    @Test public void resolvesDexStyleLocalArgumentsAndRemovesOnlyProbeExclusiveSetup() {
        JimpleBody body = body("locals"); String signature = body.getMethod().getSignature();
        Local tag = local(body, "tag", string()), message = local(body, "m", string());
        Local copied = local(body, "copy", string()), result = local(body, "x", IntType.v());
        body.getUnits().add(Jimple.v().newAssignStmt(tag, StringConstant.v("TEST")));
        body.getUnits().add(Jimple.v().newAssignStmt(message, StringConstant.v("STATEMENT=" + signature + "|x = 1|3")));
        body.getUnits().add(Jimple.v().newAssignStmt(copied, message));
        body.getUnits().add(invoke(log, copied, tag));
        body.getUnits().add(Jimple.v().newAssignStmt(result, IntConstant.v(1)));
        body.getUnits().add(invoke(custom, tag, StringConstant.v("Application message")));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertEquals(4, scan.code.getStatements().size());
        assertEquals("tag = \"TEST\"", scan.code.getStatements().get(0).getText());
        assertEquals("x = 1", scan.code.getStatements().get(1).getText());
        assertTrue(scan.code.getStatements().get(1).isEligible());
        assertTrue(scan.code.getStatements().get(2).getText().contains("Application message"));
        assertEquals(1, scan.expected.get("statements").size());
    }

    @Test public void customLocationsWorkWithoutAnyAutomaticProbeAndLegacyIdsRemainExplicitlyAbsent() {
        JimpleBody body = body("customOnly");
        body.getUnits().add(custom("CUSTOM=legacy message"));
        body.getUnits().add(Jimple.v().newNopStmt());
        body.getUnits().add(custom("CUSTOM=new message"));
        body.getUnits().add(custom("CUSTOM_POINT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertTrue(scan.foundProbes); assertTrue(scan.expected.isEmpty());
        assertEquals(2, scan.code.getStatements().size()); assertEquals(2, scan.customPoints.size());
        assertNull(scan.customPoints.get(0).get("id")); assertEquals(1, scan.customPoints.get(0).get("index"));
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", scan.customPoints.get(1).get("id"));
        assertEquals(2, scan.customPoints.get(1).get("index"));
    }

    @Test public void variableTemplatesRemainPortableAndDoNotHideOriginalValueDefinitions() {
        SootClass helper = type("com.jordansamhi.androlog.runtime.CustomLogRuntime", object);
        RefType builderType = type("java.lang.StringBuilder", object).getType();
        SootMethod create = method(helper, "create", Collections.emptyList(), builderType);
        SootMethod text = method(helper, "text", Arrays.asList(builderType, string()), VoidType.v());
        SootMethod value = method(helper, "value", Arrays.asList(builderType, IntType.v()), VoidType.v());
        SootMethod write = method(helper, "write", Arrays.asList(string(), string(), string(), builderType), VoidType.v());
        JimpleBody body = body("dynamic");
        Local number = local(body, "i3", IntType.v()), copied = local(body, "copy", IntType.v());
        Local builder = local(body, "$custom", builderType), template = local(body, "$template", string());
        body.getUnits().add(Jimple.v().newAssignStmt(number, IntConstant.v(42)));
        body.getUnits().add(Jimple.v().newAssignStmt(copied, number));
        body.getUnits().add(Jimple.v().newAssignStmt(builder, Jimple.v().newStaticInvokeExpr(create.makeRef())));
        body.getUnits().add(invoke(text, builder, StringConstant.v("score=")));
        body.getUnits().add(invoke(value, builder, copied));
        body.getUnits().add(Jimple.v().newAssignStmt(template, StringConstant.v("score={i3}")));
        body.getUnits().add(invoke(write, StringConstant.v("TEST"), StringConstant.v("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), template, builder));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertTrue(scan.foundProbes); assertTrue(scan.expected.isEmpty());
        assertEquals(3, scan.code.getStatements().size());
        assertEquals("i3 = 42", scan.code.getStatements().get(0).getText());
        assertEquals("copy = i3", scan.code.getStatements().get(1).getText());
        assertEquals("return", scan.code.getStatements().get(2).getText());
        Map<String, Object> point = scan.customPoints.get(0);
        assertEquals("score={i3}", point.get("message")); assertEquals(Boolean.TRUE, point.get("dynamic"));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", point.get("id")); assertEquals(3, point.get("index"));
        assertTrue(InstrumentedCode.isHelper(helper.getName()));
        assertFalse(InstrumentedCode.inspect(body, "OTHER", null).foundProbes);
    }

    @Test public void narrowPrimitiveHelperOverloadsAreHiddenWithoutLosingTheOriginalAssignment() {
        SootClass helper = type("com.jordansamhi.androlog.runtime.CustomLogRuntime", object);
        RefType builderType = type("java.lang.StringBuilder", object).getType();
        SootMethod create = method(helper, "create", Collections.emptyList(), builderType);
        SootMethod write = method(helper, "write", Arrays.asList(string(), string(), string(), builderType), VoidType.v());
        for (Type primitive : Arrays.asList(ByteType.v(), ShortType.v())) {
            SootMethod value = method(helper, "value", Arrays.asList(builderType, primitive), VoidType.v());
            JimpleBody body = body("narrow" + primitive);
            Local number = local(body, "number", primitive), builder = local(body, "$custom", builderType);
            body.getUnits().add(Jimple.v().newAssignStmt(number, IntConstant.v(7)));
            body.getUnits().add(Jimple.v().newAssignStmt(builder, Jimple.v().newStaticInvokeExpr(create.makeRef())));
            body.getUnits().add(invoke(value, builder, number));
            body.getUnits().add(invoke(write, StringConstant.v("TEST"), StringConstant.v("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    StringConstant.v("{number}"), builder));
            body.getUnits().add(Jimple.v().newReturnVoidStmt());
            InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
            assertEquals(2, scan.code.getStatements().size());
            assertEquals("number = 7", scan.code.getStatements().get(0).getText());
            assertEquals("return", scan.code.getStatements().get(1).getText());
            assertEquals(2, scan.customPoints.get(0).get("index"));
        }
    }

    @Test public void applicationLoggingAndUnpairedMarkersAreNotHiddenByTheirTagOrTextAlone() {
        JimpleBody body = body("ordinary");
        SootMethod appLog = method(application, "log", Arrays.asList(string(), string()), VoidType.v());
        body.getUnits().add(custom("plain app message"));
        body.getUnits().add(invoke(custom, StringConstant.v("OTHER"), StringConstant.v("CUSTOM=other tag")));
        body.getUnits().add(invoke(appLog, StringConstant.v("TEST"), StringConstant.v("CUSTOM=not AndroLog")));
        body.getUnits().add(custom("CUSTOM_POINT=cccccccccccccccccccccccccccccccc"));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertFalse(scan.foundProbes); assertTrue(scan.expected.isEmpty()); assertTrue(scan.customPoints.isEmpty());
        assertEquals(5, scan.code.getStatements().size());
    }

    @Test public void multipleReachingDefinitionsDoNotCreateInventedExpectedEvents() {
        JimpleBody body = body("ambiguous");
        Local message = local(body, "message", string()), flag = local(body, "flag", IntType.v());
        Unit probe = invoke(log, message, StringConstant.v("TEST"));
        Unit second = Jimple.v().newAssignStmt(message, StringConstant.v("CLASS=example.App"));
        body.getUnits().add(Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), second));
        body.getUnits().add(Jimple.v().newAssignStmt(message, StringConstant.v("METHOD=" + body.getMethod().getSignature())));
        body.getUnits().add(Jimple.v().newGotoStmt(probe)); body.getUnits().add(second); body.getUnits().add(probe);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertFalse(scan.foundProbes); assertTrue(scan.expected.isEmpty());
    }

    @Test public void displayBranchTargetsSkipInstrumentationWithoutMutatingTheBody() {
        JimpleBody body = body("branch"); String signature = body.getMethod().getSignature();
        Local value = local(body, "x", IntType.v());
        Unit targetProbe = probe("STATEMENT=" + signature + "|x = 9|7");
        IfStmt branch = Jimple.v().newIfStmt(Jimple.v().newEqExpr(value, IntConstant.v(0)), targetProbe);
        body.getUnits().add(branch); body.getUnits().add(Jimple.v().newNopStmt());
        body.getUnits().add(targetProbe); body.getUnits().add(Jimple.v().newAssignStmt(value, IntConstant.v(9)));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertFalse(scan.code.getStatements().get(0).getText().contains("LogCheckerClass"));
        assertTrue(scan.code.getStatements().get(0).getText().contains("x = 9"));
        assertSame(targetProbe, branch.getTarget());
    }

    @Test public void identicalConstantsAtAControlFlowMergeRemainRecognizable() {
        JimpleBody body = body("mergedConstant");
        Local message = local(body, "message", string()), flag = local(body, "flag", IntType.v());
        Unit probe = invoke(log, message, StringConstant.v("TEST"));
        Unit second = Jimple.v().newAssignStmt(message, StringConstant.v("CLASS=example.App"));
        body.getUnits().add(Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), second));
        body.getUnits().add(Jimple.v().newAssignStmt(message, StringConstant.v("CLASS=example.App")));
        body.getUnits().add(Jimple.v().newGotoStmt(probe)); body.getUnits().add(second); body.getUnits().add(probe);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertTrue(scan.foundProbes);
        assertEquals(Collections.singleton("example.App"), scan.expected.get("classes"));
    }

    @Test public void cyclicBranchTargetsAreRenderedWithoutRecursingOrChangingTheBody() {
        JimpleBody body = body("loop");
        Unit probe = probe("STATEMENT=" + body.getMethod().getSignature() + "|goto [?= (branch)]|1");
        GotoStmt jump = Jimple.v().newGotoStmt(probe);
        body.getUnits().add(probe); body.getUnits().add(jump);
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "TEST", null);
        assertEquals(1, scan.code.getStatements().size());
        assertFalse(scan.code.getStatements().get(0).getText().contains("LogCheckerClass"));
        assertSame(probe, jump.getTarget());
    }

    @Test public void helperClassesAreNeverIncludedAndWrongTagsDoNotClaimAProbeMatch() {
        assertTrue(InstrumentedCode.isHelper(Constants.LOG_CHECKER_CLASS));
        assertTrue(InstrumentedCode.isHelper(MethodCallInstrumenter.RUNTIME_CLASS + "$State"));
        assertFalse(InstrumentedCode.isHelper("example.LogCheckerClass"));
        JimpleBody helper = Jimple.v().newBody(log); log.setActiveBody(helper);
        helper.getUnits().add(Jimple.v().newReturnVoidStmt());
        assertNull(InstrumentedCode.inspect(helper, "TEST", null).code);
        JimpleBody body = body("tag"); body.getUnits().add(probe("CLASS=example.App"));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        InstrumentedCode.Result scan = InstrumentedCode.inspect(body, "OTHER", null);
        assertFalse(scan.foundProbes); assertTrue(scan.expected.isEmpty());
    }

    private SootClass type(String name, SootClass parent) {
        SootClass type = new SootClass(name, Modifier.PUBLIC); if (parent != null) type.setSuperclass(parent);
        Scene.v().addClass(type); return type;
    }
    private SootMethod method(SootClass owner, String name, List<Type> params, Type result) {
        SootMethod method = new SootMethod(name, params, result, Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method); return method;
    }
    private JimpleBody body(String name) {
        SootMethod method = method(application, name, Collections.emptyList(), VoidType.v());
        JimpleBody body = Jimple.v().newBody(method); method.setActiveBody(body); return body;
    }
    private RefType string() { return RefType.v("java.lang.String"); }
    private Local local(Body body, String name, Type type) {
        Local local = Jimple.v().newLocal(name, type); body.getLocals().add(local); return local;
    }
    private Unit invoke(SootMethod method, Value... args) { return Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(method.makeRef(), args)); }
    private Unit probe(String payload) { return invoke(log, StringConstant.v(payload), StringConstant.v("TEST")); }
    private Unit custom(String payload) { return invoke(custom, StringConstant.v("TEST"), StringConstant.v(payload)); }
}
