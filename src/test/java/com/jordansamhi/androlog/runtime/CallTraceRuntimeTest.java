package com.jordansamhi.androlog.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.*;

public class CallTraceRuntimeTest {
    private PrintStream original;
    private ByteArrayOutputStream captured;

    @Before public void captureOutputAndResetState() throws Exception {
        original = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
        Field emitted = CallTraceRuntime.class.getDeclaredField("EMITTED");
        emitted.setAccessible(true);
        ((Set<?>) emitted.get(null)).clear();
        Field states = CallTraceRuntime.class.getDeclaredField("STATES");
        states.setAccessible(true);
        ((ThreadLocal<?>) states.get(null)).remove();
    }

    @After public void restoreOutput() { System.setOut(original); }

    @Test public void confirmsOverrideAndInheritedImplementationRatherThanReceiverClass() throws Exception {
        Dispatch.invoke(new Override(), 11);
        Dispatch.invoke(new Inherited(), 11);
        Dispatch.invoke(new Override(), 11);
        List<Event> events = events();
        assertEquals(1, count(events, "D"));
        assertEquals(2, count(events, "O"));
        assertTrue(actuals(events).contains(sig(Override.class, "void work()")));
        assertTrue(actuals(events).contains(sig(Base.class, "void work()")));
        assertFalse(actuals(events).contains(sig(Inherited.class, "void work()")));
        assertUniqueEventIds(events);
    }

    @Test public void doesNotClaimAnInterveningExcludedOverrideOrCallbackAsDirectDispatch() throws Exception {
        Dispatch.invoke(new ExcludedOverride(), 12);
        assertEquals(1, count(events(), "D"));
        assertEquals(0, count(events(), "O"));
        Dispatch.externalCallback(new Base(), 13);
        assertEquals(2, count(events(), "D"));
        assertEquals(0, count(events(), "O"));
    }

    @Test public void recursionUsesDepthAndReportsDistinctEdgesWithoutFrequencyClaims() throws Exception {
        new Recursive().recur(8);
        assertEquals(1, count(events(), "D"));
        assertEquals(1, count(events(), "O"));
        assertEquals(sig(Recursive.class, "void recur(int)"), actuals(events()).iterator().next());
    }

    @Test public void threadLocalCorrelationAndGlobalDedupHandleConcurrentDispatch() throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final Base receiver = i % 2 == 0 ? new Base() : new Override();
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                for (int j = 0; j < 50; j++) Dispatch.invoke(receiver, 14);
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, count(events(), "D"));
        assertEquals(2, count(events(), "O"));
        assertUniqueEventIds(events());
    }

    @Test public void implicitClassInitializationIsNotThePendingCallTarget() throws Exception {
        Dispatch.initializeAndCall();
        assertEquals(1, count(events(), "D"));
        assertEquals(1, count(events(), "O"));
        assertEquals(sig(Lazy.class, "void target()"), actuals(events()).iterator().next());
    }

    @Test public void caughtExceptionsDiscardStaleCallsAndAllowLaterDispatch() throws Exception {
        Dispatch.catchAndContinue();
        List<Event> events = events();
        assertEquals(2, count(events, "D"));
        assertEquals(1, count(events, "O"));
        for (Event event : events) if (event.status.equals("O")) assertEquals("22", event.site);
    }

    @Test public void constructorEventsRemainDeclaredAndNeverExposeUninitializedReceivers() throws Exception {
        Dispatch.construct();
        assertEquals(1, count(events(), "D"));
        assertEquals(0, count(events(), "O"));
        assertEquals("constructor", events().get(0).kind);
    }

    @Test public void nullReceiverAndWrongReceiverCannotConfirmAnEdge() throws Exception {
        Dispatch.wrongReceiver();
        Dispatch.nullReceiver();
        assertEquals(2, count(events(), "D"));
        assertEquals(0, count(events(), "O"));
    }

    @Test public void failingBeforeHookClearsPendingStateWithoutAffectingTheApplication() throws Exception {
        Dispatch.failedHook();
        assertEquals(1, count(events(), "D"));
        assertEquals(0, count(events(), "O"));
    }

    @Test public void reentrantEmissionDoesNotReleaseTheOuterGuard() throws Exception {
        final List<String> lines = new ArrayList<>();
        System.setOut(new PrintStream(new ByteArrayOutputStream()) {
            @java.lang.Override public void println(String value) {
                lines.add(value);
                CallTraceRuntime.enter(sig(Base.class, "void work()"), new Base());
                CallTraceRuntime.before("tag", sig(Dispatch.class, "void wrongReceiver()"), 99,
                        sig(Base.class, "void work()"), "virtual", new Base());
            }
        });
        Dispatch.invoke(new Base(), 15);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("|D|15|"));
        assertTrue(lines.get(1).contains("|O|15|"));
    }

    private List<Event> events() throws Exception {
        List<Event> result = new ArrayList<>();
        for (String line : captured.toString("UTF-8").split("\\r?\\n")) {
            int offset = line.indexOf("MC2|");
            if (offset < 0) continue;
            String[] fields = line.substring(offset).split("\\|", -1);
            assertEquals(9, fields.length);
            Event event = new Event();
            event.trace = fields[1]; event.sequence = fields[2]; event.status = fields[3]; event.site = fields[4];
            event.caller = URLDecoder.decode(fields[5], "UTF-8");
            event.declared = URLDecoder.decode(fields[6], "UTF-8");
            event.actual = URLDecoder.decode(fields[7], "UTF-8"); event.kind = fields[8];
            result.add(event);
        }
        return result;
    }

    private static int count(List<Event> events, String status) {
        int count = 0;
        for (Event event : events) if (event.status.equals(status)) count++;
        return count;
    }

    private static Set<String> actuals(List<Event> events) {
        Set<String> result = new HashSet<>();
        for (Event event : events) if (event.status.equals("O")) result.add(event.actual);
        return result;
    }

    private static void assertUniqueEventIds(List<Event> events) {
        Set<String> identities = new HashSet<>();
        for (Event event : events) assertTrue(identities.add(event.trace + ":" + event.sequence));
    }

    private static String sig(Class<?> type, String method) { return "<" + type.getName() + ": " + method + ">"; }

    private static final class Event {
        String trace, sequence, status, site, caller, declared, actual, kind;
    }

    static class Base {
        void work() { CallTraceRuntime.enter(sig(Base.class, "void work()"), this); }
    }
    static class Override extends Base {
        @java.lang.Override void work() { CallTraceRuntime.enter(sig(Override.class, "void work()"), this); }
    }
    static class Inherited extends Base { }
    static class ExcludedOverride extends Base {
        @java.lang.Override void work() { super.work(); }
    }
    static class Recursive {
        void recur(int remaining) {
            String signature = sig(Recursive.class, "void recur(int)");
            CallTraceRuntime.enter(signature, this);
            if (remaining > 0) {
                long token = CallTraceRuntime.before("tag", signature, 7, signature, "virtual", this);
                recur(remaining - 1);
                CallTraceRuntime.after(token);
            }
        }
    }
    static class External {
        static void callback(Base target) { target.work(); }
        static void fail() { throw new IllegalStateException("application exception"); }
    }
    static class Lazy {
        static { Other.target(); }
        static void target() { CallTraceRuntime.enter(sig(Lazy.class, "void target()"), null); }
    }
    static class Other {
        static void target() { CallTraceRuntime.enter(sig(Other.class, "void target()"), null); }
    }
    static class Constructed {
        Constructed() { CallTraceRuntime.enter(sig(Constructed.class, "void <init>()"), this); }
    }
    static class Dispatch {
        static void invoke(Base target, int site) {
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void invoke(" + Base.class.getName() + ",int)"),
                    site, sig(Base.class, "void work()"), "virtual", target);
            target.work();
            CallTraceRuntime.after(token);
        }
        static void externalCallback(Base target, int site) {
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void externalCallback(" + Base.class.getName() + ",int)"),
                    site, sig(Base.class, "void work()"), "virtual", target);
            External.callback(target);
            CallTraceRuntime.after(token);
        }
        static void initializeAndCall() {
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void initializeAndCall()"),
                    20, sig(Lazy.class, "void target()"), "static", null);
            Lazy.target();
            CallTraceRuntime.after(token);
        }
        static void catchAndContinue() {
            Base target = new Base();
            try {
                long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void catchAndContinue()"),
                        21, sig(Base.class, "void work()"), "virtual", target);
                External.fail();
                CallTraceRuntime.after(token);
            } catch (IllegalStateException expected) {
                CallTraceRuntime.caught();
                target.work(); // A stale token must not claim this later, unpaired invocation.
            }
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void catchAndContinue()"),
                    22, sig(Base.class, "void work()"), "virtual", target);
            target.work();
            CallTraceRuntime.after(token);
        }
        static void construct() {
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void construct()"),
                    23, sig(Constructed.class, "void <init>()"), "constructor", null);
            new Constructed();
            CallTraceRuntime.after(token);
        }
        static void wrongReceiver() {
            Base target = new Base();
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void wrongReceiver()"),
                    24, sig(Base.class, "void work()"), "virtual", new Base());
            target.work();
            CallTraceRuntime.after(token);
        }
        static void nullReceiver() {
            long token = CallTraceRuntime.before("tag", sig(Dispatch.class, "void nullReceiver()"),
                    25, sig(Base.class, "void work()"), "virtual", null);
            try {
                ((Base) null).work();
                fail("The application's null receiver must still throw");
            } catch (NullPointerException expected) {
                CallTraceRuntime.caught();
            }
            CallTraceRuntime.after(token);
        }
        static void failedHook() {
            Base target = new Base();
            CallTraceRuntime.before("tag", sig(Dispatch.class, "void failedHook()"),
                    26, sig(Base.class, "void work()"), "virtual", target);
            assertEquals(0, CallTraceRuntime.before("tag", null, 27, null, "virtual", target));
            target.work();
        }
    }
}
