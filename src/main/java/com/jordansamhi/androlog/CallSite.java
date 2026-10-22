package com.jordansamhi.androlog;

import soot.jimple.*;

/** A call instruction in the original, uninstrumented Jimple body. */
public final class CallSite {
    public final String caller;
    public final int line;
    public final String declared;
    public final String kind;

    public CallSite(String caller, int line, String declared, String kind) {
        this.caller = caller;
        this.line = line;
        this.declared = declared;
        this.kind = kind;
    }

    public String key() { return key(caller, line); }
    public static String key(String caller, int line) { return caller + "|" + line; }

    public static String kind(InvokeExpr invoke) {
        if ("<init>".equals(invoke.getMethodRef().name())) return "constructor";
        if (invoke instanceof StaticInvokeExpr) return "static";
        if (invoke instanceof InterfaceInvokeExpr) return "interface";
        if (invoke instanceof VirtualInvokeExpr) return "virtual";
        if (invoke instanceof SpecialInvokeExpr) return "special";
        return "dynamic";
    }
}
