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
    private static String write(StringBuilder value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try { System.setOut(new PrintStream(output, true, "UTF-8")); CustomLogRuntime.write("TEST", "abc", "template", value); }
        finally { System.setOut(previous); }
        return output.toString("UTF-8");
    }
}
