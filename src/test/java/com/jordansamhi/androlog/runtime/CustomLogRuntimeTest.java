package com.jordansamhi.androlog.runtime;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class CustomLogRuntimeTest {
    @Test public void typedValuesAreSingleLineAndNullSafe() throws Exception {
        StringBuilder value = CustomLogRuntime.create();
        CustomLogRuntime.text(value, "values="); CustomLogRuntime.value(value, -7); CustomLogRuntime.text(value, ",");
        CustomLogRuntime.value(value, 1234567890123L); CustomLogRuntime.text(value, ","); CustomLogRuntime.value(value, 1.5f);
        CustomLogRuntime.text(value, ","); CustomLogRuntime.value(value, 2.25d); CustomLogRuntime.text(value, ","); CustomLogRuntime.value(value, true);
        CustomLogRuntime.text(value, ","); CustomLogRuntime.value(value, (String) null); CustomLogRuntime.text(value, ","); CustomLogRuntime.value(value, "a\nb\r\0\\c");
        String log = write(value);
        assertTrue(log.contains("CUSTOM_VALUE=abc|values=-7,1234567890123,1.5,2.25,true,null,a\\nb\\r\\0\\\\c"));
        assertTrue(log.contains("CUSTOM_POINT=abc")); assertEquals(2, log.split("\\R").length);
    }
    @Test public void largeUnicodeValuesAreBoundedWithoutSplittingCodepoints() throws Exception {
        StringBuilder value = CustomLogRuntime.create();
        StringBuilder input = new StringBuilder(); for (int i = 0; i < 5000; i++) input.append("😀");
        CustomLogRuntime.value(value, input.toString());
        assertTrue(value.length() <= 3001);
        String line = write(value).split("\\R")[0]; String payload = line.substring(line.indexOf('|') + 1);
        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 3000); assertTrue(payload.endsWith("..."));
        assertFalse(payload.contains("\ufffd"));
    }
    @Test public void objectProbeUsesActualToStringAndPreservesMarkersAndNulls() throws Exception {
        Object custom = new Object() { @Override public String toString() { return "custom\nvalue\\end"; } };
        assertTrue(objectLog(custom).contains("CUSTOM_VALUE=object|custom\\nvalue\\\\end"));
        assertTrue(objectLog(custom).contains("CUSTOM_POINT=object"));
        assertTrue(objectLog(null).contains("CUSTOM_VALUE=object|null"));
        Object plain = new Object();
        assertTrue(objectLog(plain).contains("CUSTOM_VALUE=object|" + plain.toString()));
        int[] array = new int[] {1, 2};
        assertTrue(objectLog(array).contains("CUSTOM_VALUE=object|" + array.toString()));
        assertTrue(objectLog(new Object() { @Override public String toString() { return null; } }).contains("CUSTOM_VALUE=object|null"));
    }
    @Test public void ordinaryConversionFailuresDoNotEscapeAndDoNotPoisonTheNextLog() throws Exception {
        assertTrue(objectLog(new Object() { @Override public String toString() { throw new IllegalStateException("broken"); } })
                .contains("CUSTOM_VALUE=object|<toString failed>"));
        assertTrue(objectLog(new Object() { @Override public String toString() { throw new AssertionError("broken"); } })
                .contains("CUSTOM_VALUE=object|<toString failed>"));
        assertTrue(objectLog(new Object() { @Override public String toString() { return "healthy"; } })
                .contains("CUSTOM_VALUE=object|healthy"));
    }
    @Test public void anInstrumentedToStringCannotRecursivelyInvokeItselfThroughObjectLogging() throws Exception {
        final int[] calls = {0};
        Object recursive = new Object() {
            @Override public String toString() {
                calls[0]++;
                CustomLogRuntime.logObject("TEST", "nested", "{self}", this);
                return "outer";
            }
        };
        String log = objectLog(recursive);
        assertEquals(1, calls[0]);
        assertTrue(log.contains("CUSTOM_VALUE=nested|<recursive toString>"));
        assertTrue(log.contains("CUSTOM_POINT=nested"));
        assertTrue(log.contains("CUSTOM_VALUE=object|outer"));
        assertTrue(log.contains("CUSTOM_POINT=object"));
    }
    @Test public void fatalVmErrorsPropagateAndStillReleaseTheConversionGuard() throws Exception {
        try {
            objectLog(new Object() { @Override public String toString() { throw new OutOfMemoryError("synthetic test failure"); } });
            fail("Fatal VM failures must propagate");
        } catch (OutOfMemoryError expected) { assertEquals("synthetic test failure", expected.getMessage()); }
        assertTrue(objectLog(new Object() { @Override public String toString() { return "healthy"; } })
                .contains("CUSTOM_VALUE=object|healthy"));
    }
    @Test public void objectRepresentationsReuseUnicodeOutputLimits() throws Exception {
        final StringBuilder text = new StringBuilder(); for (int i = 0; i < 5000; i++) text.append("😀");
        String log = objectLog(new Object() { @Override public String toString() { return text.toString(); } });
        String line = log.split("\\R")[0], value = line.substring(line.indexOf('|') + 1);
        assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= 3000);
        assertTrue(value.endsWith("...")); assertFalse(value.contains("\ufffd"));
    }
    private static String objectLog(Object value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try { System.setOut(new PrintStream(output, true, "UTF-8")); CustomLogRuntime.logObject("TEST", "object", "{value}", value); }
        finally { System.setOut(previous); }
        return output.toString("UTF-8");
    }
    private static String write(StringBuilder value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try { System.setOut(new PrintStream(output, true, "UTF-8")); CustomLogRuntime.write("TEST", "abc", "template", value); }
        finally { System.setOut(previous); }
        return output.toString("UTF-8");
    }
}
