package com.jordansamhi.androlog.instrumentation;

import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.Body;
import soot.BodyTransformer;
import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethodRef;
import soot.Transform;
import soot.Unit;
import soot.jimple.IdentityStmt;
import soot.jimple.Jimple;
import soot.jimple.MonitorStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records arrival at a statement, including branches and operations that subsequently throw. */
public final class StatementInstrumenter {
    private final String tag;
    private final boolean includeLibraries;
    private final String targetPackage;
    private final SootMethodRef logger;

    /** AndroSpecter's Logger must have initialized its helper before constructing this transform. */
    public StatementInstrumenter(String tag, boolean includeLibraries, String targetPackage) {
        this.tag = tag;
        this.includeLibraries = includeLibraries;
        this.targetPackage = targetPackage;
        this.logger = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS).getMethod(Constants.SUB_SIG_LOG).makeRef();
    }

    /** Register after callsite capture, but before the other coverage transforms alter the body. */
    public void register() {
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologStatements", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) {
                instrument(body);
            }
        }));
    }

    /** Shared with baseline collection: retain the existing statement IDs and exclusions. */
    public static boolean isEligible(Stmt statement) {
        return !(statement instanceof IdentityStmt || statement instanceof ReturnStmt
                || statement instanceof ReturnVoidStmt || statement instanceof MonitorStmt);
    }

    void instrument(Body body) {
        SootClass owner = body.getMethod().getDeclaringClass();
        String name = owner.getName();
        if (!body.getMethod().isConcrete() || name.equals(Constants.LOG_CHECKER_CLASS)
                || name.equals(MethodCallInstrumenter.RUNTIME_CLASS)
                || name.startsWith(MethodCallInstrumenter.RUNTIME_CLASS + "$")
                || (targetPackage != null && !owner.getPackageName().startsWith(targetPackage))
                || (!includeLibraries && LibrariesManager.v().isLibrary(owner))) return;

        List<Unit> originals = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        int index = 0;
        for (Unit unit : body.getUnits()) {
            index++;
            if (!isEligible((Stmt) unit)) continue;
            originals.add(unit);
            // Capture every message before redirecting jumps: their textual targets must stay original.
            messages.add("STATEMENT=" + body.getMethod().getSignature() + "|" + unit + "|" + index);
        }
        for (int i = 0; i < originals.size(); i++) {
            Unit probe = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logger,
                    StringConstant.v(messages.get(i)), StringConstant.v(tag)));
            // Redirect incoming branches and trap boundaries with the original statement. Caught-exception
            // identities stay first in handlers, and constructor probes never access uninitialized this.
            body.getUnits().insertBefore(probe, originals.get(i));
        }
        body.validate();
    }
}
