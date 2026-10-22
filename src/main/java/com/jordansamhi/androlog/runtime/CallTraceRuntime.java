package com.jordansamhi.androlog.runtime;

import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime call correlation. Only a directly observed, matching instrumented entry confirms an edge.
 * This class deliberately uses APIs available on old Android versions and has no AndroLog dependency.
 */
public final class CallTraceRuntime {
    private static final String TRACE = UUID.randomUUID().toString();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>();
    private static final Set<String> EMITTED = new HashSet<String>();
    private static final int MAX_PENDING = 256;

    private CallTraceRuntime() { }

    public static long before(String tag, String caller, int site, String declared, String kind, Object receiver) {
        State state = null;
        boolean entered = false;
        try {
            state = state();
            if (state.busy) return 0;
            state.busy = true;
            entered = true;
            StackTraceElement[] frames = frames();
            prune(state, frames.length);
            emitOnce(tag, "D", caller, site, declared, "", kind);
            if ("constructor".equals(kind) || "dynamic".equals(kind)
                    || !matches(frames, 0, caller)) return 0;
            if (state.pending.size() >= MAX_PENDING) state.pending.remove(0);
            long token = ++state.nextToken;
            state.pending.add(new Pending(token, tag, caller, site, declared, kind, receiver, frames));
            return token;
        } catch (Throwable ignored) {
            if (state != null) state.pending.clear();
            return 0;
        } finally {
            if (entered) state.busy = false;
        }
    }

    public static void enter(String actual, Object receiver) {
        State state = null;
        boolean entered = false;
        try {
            state = STATES.get();
            if (state == null || state.busy) return;
            state.busy = true;
            entered = true;
            StackTraceElement[] frames = frames();
            prune(state, frames.length);
            if (state.pending.isEmpty() || actual.indexOf(" <clinit>(") >= 0
                    || actual.indexOf(" <init>(") >= 0 || !matches(frames, 0, actual)) return;
            Pending pending = state.pending.get(state.pending.size() - 1);
            if (pending.matched || frames.length != pending.frames.length + 1
                    || !sameSuffix(frames, pending.frames)
                    || !subsignature(actual).equals(subsignature(pending.declared))) return;
            if ("static".equals(pending.kind)) {
                if (receiver != null) return;
            } else if (receiver == null || pending.receiver.get() != receiver) {
                return;
            }
            pending.matched = true;
            emitOnce(pending.tag, "O", pending.caller, pending.site, pending.declared, actual, pending.kind);
        } catch (Throwable ignored) {
            if (state != null) state.pending.clear();
        } finally {
            if (entered) state.busy = false;
        }
    }

    public static void after(long token) {
        State state = null;
        boolean entered = false;
        try {
            state = STATES.get();
            if (state == null || state.busy || token == 0) return;
            state.busy = true;
            entered = true;
            for (int i = state.pending.size() - 1; i >= 0; i--) {
                if (state.pending.get(i).token == token) {
                    while (state.pending.size() > i) state.pending.remove(state.pending.size() - 1);
                    return;
                }
            }
        } catch (Throwable ignored) {
            if (state != null) state.pending.clear();
        } finally {
            if (entered) state.busy = false;
        }
    }

    /** Called immediately after an original caught-exception identity, without modifying trap ranges. */
    public static void caught() {
        State state = null;
        boolean entered = false;
        try {
            state = STATES.get();
            if (state == null || state.busy) return;
            state.busy = true;
            entered = true;
            prune(state, frames().length);
        } catch (Throwable ignored) {
            if (state != null) state.pending.clear();
        } finally {
            if (entered) state.busy = false;
        }
    }

    private static State state() {
        State state = STATES.get();
        if (state == null) {
            state = new State();
            STATES.set(state);
        }
        return state;
    }

    private static void prune(State state, int currentDepth) {
        for (int i = state.pending.size() - 1; i >= 0; i--) {
            if (state.pending.get(i).frames.length >= currentDepth) state.pending.remove(i);
        }
    }

    private static StackTraceElement[] frames() {
        StackTraceElement[] full = Thread.currentThread().getStackTrace();
        // A plain name avoids Soot 4.4.1's Coffi fallback emitting a malformed dex const-class descriptor.
        String helper = "com.jordansamhi.androlog.runtime.CallTraceRuntime";
        int start = -1;
        for (int i = 0; i < full.length; i++) {
            if (helper.equals(full[i].getClassName())) start = i + 1;
            else if (start >= 0) break;
        }
        if (start < 0 || start >= full.length) return new StackTraceElement[0];
        StackTraceElement[] result = new StackTraceElement[full.length - start];
        System.arraycopy(full, start, result, 0, result.length);
        return result;
    }

    private static boolean sameSuffix(StackTraceElement[] entered, StackTraceElement[] called) {
        if (entered.length != called.length + 1) return false;
        for (int i = 0; i < called.length; i++) {
            StackTraceElement left = entered[i + 1];
            StackTraceElement right = called[i];
            if (!left.getClassName().equals(right.getClassName())
                    || !left.getMethodName().equals(right.getMethodName())) return false;
        }
        return true;
    }

    private static boolean matches(StackTraceElement[] frames, int index, String signature) {
        if (index >= frames.length) return false;
        int colon = signature.indexOf(": ");
        int open = signature.indexOf('(', colon + 2);
        int name = open < 0 ? -1 : signature.lastIndexOf(' ', open);
        return colon > 1 && name > colon && signature.charAt(0) == '<'
                && frames[index].getClassName().equals(signature.substring(1, colon))
                && frames[index].getMethodName().equals(signature.substring(name + 1, open));
    }

    private static String subsignature(String signature) {
        int colon = signature.indexOf(": ");
        return colon < 0 ? "" : signature.substring(colon + 2);
    }

    private static void emitOnce(String tag, String status, String caller, int site,
                                 String declared, String actual, String kind) throws Exception {
        String key = status + ":" + caller.length() + ":" + caller + ":" + site + ":" + actual.length() + ":" + actual;
        synchronized (EMITTED) {
            if (!EMITTED.add(key)) return;
            try {
                String message = "MC2|" + TRACE + "|" + SEQUENCE.incrementAndGet() + "|" + status + "|" + site
                        + "|" + URLEncoder.encode(caller, "UTF-8") + "|" + URLEncoder.encode(declared, "UTF-8")
                        + "|" + URLEncoder.encode(actual, "UTF-8") + "|" + kind;
                emit(tag, message);
            } catch (Throwable failure) {
                EMITTED.remove(key);
                throw failure;
            }
        }
    }

    /** Replaced with android.util.Log.i by the APK instrumenter; stdout supports plain JVM tests. */
    private static void emit(String tag, String message) {
        System.out.println(tag + ": " + message);
    }

    private static final class State {
        final ArrayList<Pending> pending = new ArrayList<Pending>();
        long nextToken;
        boolean busy;
        State() { }
    }

    private static final class Pending {
        final long token;
        final String tag;
        final String caller;
        final int site;
        final String declared;
        final String kind;
        final WeakReference<Object> receiver;
        final StackTraceElement[] frames;
        boolean matched;

        Pending(long token, String tag, String caller, int site, String declared, String kind,
                Object receiver, StackTraceElement[] frames) {
            this.token = token;
            this.tag = tag;
            this.caller = caller;
            this.site = site;
            this.declared = declared;
            this.kind = kind;
            this.receiver = new WeakReference<Object>(receiver);
            this.frames = frames;
        }
    }
}
