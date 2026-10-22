package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androlog.runtime.CallTraceRuntime;
import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.Body;
import soot.BodyTransformer;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.PackManager;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.SourceLocator;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NullConstant;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.VirtualInvokeExpr;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Captures original callsites first and inserts dispatch-correlation hooks after all coverage probes. */
public final class MethodCallInstrumenter {
    public static final String RUNTIME_CLASS = "com.jordansamhi.androlog.runtime.CallTraceRuntime";
    private static final String[] RUNTIME_CLASSES = {RUNTIME_CLASS, RUNTIME_CLASS + "$State", RUNTIME_CLASS + "$Pending"};
    private final String tag;
    private final boolean includeLibraries;
    private final String targetPackage;
    private final Map<SootMethod, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final List<SootClass> runtimeClasses = new ArrayList<>();
    private SootMethod before;
    private SootMethod enter;
    private SootMethod after;
    private SootMethod caught;

    public MethodCallInstrumenter(String tag, boolean includeLibraries, String targetPackage) {
        this.tag = tag;
        this.includeLibraries = includeLibraries;
        this.targetPackage = targetPackage;
    }

    /** Register before existing coverage transformers, preserving their original statement identifiers. */
    public void registerCapture() {
        loadRuntimeClasses();
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologCallCapture", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) {
                capture(body);
            }
        }));
    }

    /** Register after existing coverage transformers; only previously captured original Units are instrumented. */
    public void registerInstrumentation() {
        if (before == null) throw new IllegalStateException("Register call capture before call instrumentation.");
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologCallInstrumentation", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) {
                instrument(body);
            }
        }));
    }

    /** Call after runPacks and before APK export so helper code never enters coverage transformations. */
    public void includeRuntimeClasses() {
        for (SootClass runtime : runtimeClasses) runtime.setApplicationClass();
    }

    private boolean selected(SootMethod method) {
        SootClass declaring = method.getDeclaringClass();
        String name = declaring.getName();
        return method.isConcrete() && !name.equals(Constants.LOG_CHECKER_CLASS)
                && !name.equals(RUNTIME_CLASS) && !name.startsWith(RUNTIME_CLASS + "$")
                && (targetPackage == null || declaring.getPackageName().startsWith(targetPackage))
                && (includeLibraries || !LibrariesManager.v().isLibrary(declaring));
    }

    void capture(Body body) {
        if (!selected(body.getMethod())) return;
        Snapshot snapshot = new Snapshot();
        int index = 0;
        for (Unit unit : body.getUnits()) {
            index++;
            Stmt statement = (Stmt) unit;
            if (statement instanceof IdentityStmt
                    && ((IdentityStmt) statement).getRightOp() instanceof CaughtExceptionRef) {
                snapshot.handlers.add(unit);
            }
            if (!statement.containsInvokeExpr()) continue;
            InvokeExpr invocation = statement.getInvokeExpr();
            String kind = invocation.getMethodRef().name().equals("<init>") ? "constructor"
                    : invocation instanceof DynamicInvokeExpr ? "dynamic"
                    : invocation instanceof StaticInvokeExpr ? "static"
                    : invocation instanceof InterfaceInvokeExpr ? "interface"
                    : invocation instanceof VirtualInvokeExpr ? "virtual"
                    : invocation instanceof SpecialInvokeExpr ? "special" : "dynamic";
            Value receiver = invocation instanceof InstanceInvokeExpr && !"constructor".equals(kind)
                    ? ((InstanceInvokeExpr) invocation).getBase() : NullConstant.v();
            snapshot.sites.add(new Site(unit, index, invocation.getMethodRef().getSignature(), kind, receiver));
        }
        snapshots.put(body.getMethod(), snapshot);
    }

    void instrument(Body body) {
        Snapshot snapshot = snapshots.remove(body.getMethod());
        if (snapshot == null) return;
        SootMethod method = body.getMethod();
        // Constructors cannot expose uninitialized this; class initialization is never a dispatched target.
        if (!method.isConstructor() && !method.isStaticInitializer()) {
            Unit entry = ((JimpleBody) body).getFirstNonIdentityStmt();
            Value receiver = method.isStatic() ? NullConstant.v() : body.getThisLocal();
            Unit hook = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(enter.makeRef(),
                    StringConstant.v(method.getSignature()), receiver));
            // No redirection: a loop targeting the former entry must not masquerade as another method entry.
            body.getUnits().insertBeforeNoRedirect(hook, entry);
        }
        for (Unit handler : snapshot.handlers) {
            body.getUnits().insertAfter(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(caught.makeRef())), handler);
        }
        int serial = 0;
        for (Site site : snapshot.sites) {
            if (!body.getUnits().contains(site.unit)) {
                throw new IllegalStateException("An original callsite was removed before call instrumentation: " + method);
            }
            Local token = Jimple.v().newLocal(uniqueLocal(body, "$andrologCall" + serial++), LongType.v());
            body.getLocals().add(token);
            Unit start = Jimple.v().newAssignStmt(token, Jimple.v().newStaticInvokeExpr(before.makeRef(),
                    StringConstant.v(tag), StringConstant.v(method.getSignature()), IntConstant.v(site.index),
                    StringConstant.v(site.declared), StringConstant.v(site.kind), site.receiver));
            body.getUnits().insertBefore(start, site.unit);
            body.getUnits().insertAfter(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(after.makeRef(), token)), site.unit);
        }
        body.validate();
    }

    private static String uniqueLocal(Body body, String candidate) {
        String name = candidate;
        boolean found;
        do {
            found = false;
            for (Local local : body.getLocals()) {
                if (local.getName().equals(name)) { found = true; name += "_"; break; }
            }
        } while (found);
        return name;
    }

    private void loadRuntimeClasses() {
        if (!runtimeClasses.isEmpty()) throw new IllegalStateException("Call capture is already registered.");
        for (String name : RUNTIME_CLASSES) {
            if (Scene.v().containsClass(name) && !Scene.v().getSootClass(name).isPhantom()) {
                throw new IllegalStateException("APK already contains the reserved call-tracing class " + name);
            }
        }
        SourceLocator.v().additionalClassLoader(new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                String prefix = RUNTIME_CLASS.replace('.', '/');
                return name.equals(prefix + ".class") || name.startsWith(prefix + "$")
                        ? CallTraceRuntime.class.getClassLoader().getResourceAsStream(name) : null;
            }
        });
        for (String name : RUNTIME_CLASSES) {
            SootClass runtime = Scene.v().forceResolve(name, SootClass.BODIES);
            if (runtime.isPhantom()) throw new IllegalStateException("Cannot load bundled call runtime " + name);
            runtime.setLibraryClass();
            runtimeClasses.add(runtime);
        }
        // Resolve all helper bodies while the source loader is available. They are not run through jtp.
        for (SootClass runtime : runtimeClasses) {
            for (SootMethod method : runtime.getMethods()) if (method.isConcrete()) method.retrieveActiveBody();
        }
        SootClass runtime = runtimeClasses.get(0);
        before = runtime.getMethod("long before(java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.Object)");
        enter = runtime.getMethod("void enter(java.lang.String,java.lang.Object)");
        after = runtime.getMethod("void after(long)");
        caught = runtime.getMethod("void caught()");
        replaceEmitter(runtime.getMethod("void emit(java.lang.String,java.lang.String)"));
    }

    private static void replaceEmitter(SootMethod method) {
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        Local tag = Jimple.v().newLocal("tag", RefType.v("java.lang.String"));
        Local message = Jimple.v().newLocal("message", RefType.v("java.lang.String"));
        body.getLocals().add(tag);
        body.getLocals().add(message);
        body.getUnits().add(Jimple.v().newIdentityStmt(tag, Jimple.v().newParameterRef(tag.getType(), 0)));
        body.getUnits().add(Jimple.v().newIdentityStmt(message, Jimple.v().newParameterRef(message.getType(), 1)));
        SootClass log = Scene.v().forceResolve("android.util.Log", SootClass.SIGNATURES);
        body.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(log, "i", Arrays.asList(tag.getType(), message.getType()), IntType.v(), true), tag, message)));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        body.validate();
    }

    private static final class Snapshot {
        final List<Site> sites = new ArrayList<>();
        final List<Unit> handlers = new ArrayList<>();
    }

    private static final class Site {
        final Unit unit;
        final int index;
        final String declared;
        final String kind;
        final Value receiver;
        Site(Unit unit, int index, String declared, String kind, Value receiver) {
            this.unit = unit;
            this.index = index;
            this.declared = declared;
            this.kind = kind;
            this.receiver = receiver;
        }
    }
}
