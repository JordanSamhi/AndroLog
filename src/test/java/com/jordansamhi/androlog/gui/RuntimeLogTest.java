package com.jordansamhi.androlog.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RuntimeLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void readsExactTagAndKeepsLiteralMessagesSeparate() throws Exception {
        Path file = temporary.newFile("runtime.log").toPath();
        Files.write(file, Arrays.asList(
            "10-28 10:00:01.100  123  123 I ANDROLOG: CLASS=com.example.Main",
            "10-28 10:00:02.100  123  123 I ANDROLOG: CLASS=com.example.Main",
            "10-28 10:01:01.000  123  123 I ANDROLOG: ACTIVITY=com.example.Main|void onCreate()",
            "I/ANDROLOG( 123): CUSTOM=CLASS=literal",
            "I/ANDROLOG( 123): CUSTOM=CLASS=literal",
            "ANDROLOG: CALL=<A: void a()>--><B: void b()>",
            "ANDROLOG_OTHER: METHOD=foreign",
            "unrelated line"), StandardCharsets.UTF_8);
        RuntimeLog result = RuntimeLog.read(file, "ANDROLOG");
        assertEquals(8, result.lines); assertEquals(6, result.recognized);
        assertEquals(Long.valueOf(2), result.entries.get("CLASS").get("com.example.Main"));
        assertEquals(Long.valueOf(2), result.entries.get("CUSTOM").get("CLASS=literal"));
        assertEquals(Long.valueOf(1), result.entries.get("ACTIVITY").get("com.example.Main"));
        assertTrue(result.entries.get("METHOD").isEmpty()); assertEquals(1, result.entries.get("CALLS").size());
        assertEquals(Long.valueOf(2), result.minutes.get("10-28 10:00"));
        assertEquals(Long.valueOf(1), result.minutes.get("10-28 10:01"));
    }
    @Test public void rejectsInvalidTags() throws Exception {
        try { RuntimeLog.read(temporary.newFile().toPath(), "bad tag"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("tag")); }
    }
    @Test public void dashboardCanBeConstructed() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> assertNotNull(new RuntimeDashboard(null)));
    }
}
