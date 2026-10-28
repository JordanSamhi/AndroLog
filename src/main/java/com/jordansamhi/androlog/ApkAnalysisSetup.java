package com.jordansamhi.androlog;

import soot.G;
import soot.Body;
import soot.BodyTransformer;
import soot.PackManager;
import soot.RefLikeType;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.Transform;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.CastExpr;
import soot.options.Options;
import soot.tagkit.BytecodeOffsetTag;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/** Initializes the pinned APK backend for both instrumentation and coverage reports. */
final class ApkAnalysisSetup {
    private ApkAnalysisSetup() { }

    static void initialize(String platforms, Path apk, Path output) {
        G.reset();
        Options options = Options.v();
        options.set_allow_phantom_refs(true);
        options.set_output_format(Options.output_format_dex);
        options.set_output_dir(output.toString());
        options.set_force_overwrite(true);
        options.set_whole_program(true);
        options.set_prepend_classpath(true);
        options.set_android_jars(platforms);
        // Source-precision numbers changed in Soot 4.7. Compile against our actual backend,
        // instead of using constants inlined in AndroSpecter 2.0's legacy setup helper.
        options.set_src_prec(Options.src_prec_apk);
        options.set_process_dir(Collections.singletonList(apk.toString()));
        options.set_include_all(true);
        options.set_keep_offset(true);
        for (String helper : new String[]{"android.util.Log", "java.util.HashSet", "java.lang.Object", "java.util.Set"}) {
            Scene.v().addBasicClass(helper, SootClass.SIGNATURES);
            Scene.v().loadClassAndSupport(helper);
        }
        Scene.v().loadNecessaryClasses();
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologDexCompatibility", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> configuration) {
                preserveInterfaceAssignments(body);
            }
        }));
    }

    static void preserveInterfaceAssignments(Body body) {
        for (Unit unit : body.getUnits()) {
            if (!(unit instanceof AssignStmt) || unit.hasTag(BytecodeOffsetTag.NAME)) continue;
            AssignStmt assignment = (AssignStmt) unit;
            if (!(assignment.getRightOp() instanceof CastExpr)) continue;
            CastExpr cast = (CastExpr) assignment.getRightOp();
            // Android permits reference assignment to an interface-typed field/local without
            // checking membership. Soot can introduce a check-cast that newly throws in valid
            // obfuscated DEX. Original check-casts carry a bytecode offset and are retained;
            // class/array casts and primitive conversions also retain their runtime semantics.
            if (cast.getCastType() instanceof RefType && cast.getOp().getType() instanceof RefLikeType
                    && ((RefType) cast.getCastType()).getSootClass().isInterface()) {
                assignment.setRightOp(cast.getOp());
            }
        }
    }
}
