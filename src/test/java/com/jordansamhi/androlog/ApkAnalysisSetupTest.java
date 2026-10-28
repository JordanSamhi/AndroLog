package com.jordansamhi.androlog;

import org.junit.After;
import org.junit.Test;
import soot.*;
import soot.baf.BafASMBackend;
import soot.jimple.*;
import soot.options.Options;
import soot.tagkit.BytecodeOffsetTag;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;

import static org.junit.Assert.*;

public class ApkAnalysisSetupTest {
    @After public void resetScene() { G.reset(); }

    @Test public void syntheticInterfaceCastsDoNotThrowButExplicitAndClassCastsStillDo() throws Exception {
        G.reset();
        Options.v().set_allow_phantom_refs(true);
        Options.v().setPhaseOption("bb", "enabled:false");
        SootClass object = new SootClass("java.lang.Object", Modifier.PUBLIC);
        Scene.v().addClass(object);
        SootClass runnable = new SootClass("java.lang.Runnable", Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
        runnable.setSuperclass(object);
        Scene.v().addClass(runnable);
        SootClass string = new SootClass("java.lang.String", Modifier.PUBLIC);
        string.setSuperclass(object);
        Scene.v().addClass(string);
        SootClass fixture = new SootClass("example.CastFixture", Modifier.PUBLIC);
        fixture.setSuperclass(object);
        Scene.v().addClass(fixture);
        fixture.setApplicationClass();
        for (String name : new String[]{"synthetic", "explicit", "classCast"}) {
            RefType target = "classCast".equals(name) ? string.getType() : runnable.getType();
            SootMethod method = new SootMethod(name, Collections.singletonList(object.getType()), target,
                    Modifier.PUBLIC | Modifier.STATIC);
            fixture.addMethod(method);
            JimpleBody body = Jimple.v().newBody(method);
            method.setActiveBody(body);
            Local input = Jimple.v().newLocal("input", object.getType());
            Local result = Jimple.v().newLocal("result", target);
            Collections.addAll(body.getLocals(), input, result);
            AssignStmt cast = Jimple.v().newAssignStmt(result, Jimple.v().newCastExpr(input, target));
            if ("explicit".equals(name)) cast.addTag(new BytecodeOffsetTag(4));
            Collections.addAll(body.getUnits(),
                    Jimple.v().newIdentityStmt(input, Jimple.v().newParameterRef(object.getType(), 0)),
                    cast, Jimple.v().newReturnStmt(result));
            ApkAnalysisSetup.preserveInterfaceAssignments(body);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BafASMBackend(fixture, Options.java_version_1_8).generateClassFile(bytes);
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadFixture() { return defineClass(fixture.getName(), bytes.toByteArray(), 0, bytes.size()); }
        }.loadFixture();
        Object input = new Object();
        assertSame(input, generated.getMethod("synthetic", Object.class).invoke(null, input));
        for (String name : new String[]{"explicit", "classCast"}) {
            try {
                generated.getMethod(name, Object.class).invoke(null, input);
                fail(name + " must retain its runtime type check");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof ClassCastException);
            }
        }
    }
}
