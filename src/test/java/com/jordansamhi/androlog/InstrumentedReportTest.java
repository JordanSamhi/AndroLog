package com.jordansamhi.androlog;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

public class InstrumentedReportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SummaryBuilder apk;
    private SummaryLogBuilder log;
    private static final String METHOD = "<example.App: void run()>";
    private static final String FIRST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", SECOND = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Before public void setup() throws Exception {
        CoverageTestSupport.resetBuilders(); apk = SummaryBuilder.v(); log = SummaryLogBuilder.v();
        apk.setInstrumentedInput("TEST");
    }
    @After public void cleanup() throws Exception { CoverageTestSupport.resetBuilders(); }

    @Test public void matchesPortableCustomPointsByIdEvenWhenMessagesAreIdentical() throws Exception {
        addPoint(FIRST, 1, "same message"); addPoint(SECOND, 2, "same message");
        Path input = temporary.newFile("runtime.log").toPath();
        Files.write(input, Arrays.asList("OTHER: CUSTOM_POINT=" + SECOND, "TEST: CUSTOM=same message",
                "TEST: CUSTOM_POINT=" + FIRST, "TEST: CUSTOM_POINT=" + FIRST), StandardCharsets.UTF_8);
        new LogParser("TEST", apk).parseLogs(input.toString());
        Map<String, Object> custom = CustomPointReport.build(apk, log);
        List<Map<String, Object>> points = points(custom);
        assertEquals("covered", points.get(0).get("status")); assertEquals(2, points.get(0).get("count"));
        assertEquals("uncovered", points.get(1).get("status")); assertEquals(0, points.get(1).get("count"));
        assertTrue(log.getSummary().isEmpty());
    }

    @Test public void emptyLogsRetainExpectedPointsAndLegacyAmbiguityIsExplicit() throws Exception {
        addPoint(null, 1, "shared"); addPoint(null, 2, "shared");
        List<Map<String, Object>> empty = points(CustomPointReport.build(apk, log));
        assertEquals("uncovered", empty.get(0).get("status"));
        assertNotEquals(empty.get(0).get("id"), empty.get(1).get("id"));
        log.recordCustomMessage("shared");
        for (Map<String, Object> point : points(CustomPointReport.build(apk, log))) {
            assertEquals("unknown", point.get("status")); assertNull(point.get("count"));
        }
    }

    @Test public void foreignMarkersAreReportedWithoutCoveringAnExpectedPoint() throws Exception {
        addPoint(FIRST, 1, "here"); log.recordCustomMarker(SECOND);
        Map<String, Object> custom = CustomPointReport.build(apk, log);
        assertEquals(1L, custom.get("unmatchedMarkers")); assertTrue(custom.containsKey("notice"));
        assertEquals("uncovered", points(custom).get(0).get("status"));
    }

    @Test public void htmlUsesOriginalProbeIdentityWithReloadedDisplayRowsAndEmbedsCustomLocations() throws Exception {
        String payload = METHOD + "|oldLocal = 42|19";
        CoverageTestSupport.addBaseline(apk, "statements", payload);
        CoverageTestSupport.addCodeMethod(apk, new CoverageMethod("example.App", METHOD, null, Arrays.asList(
                new CoverageMethod.Statement(1, "$i2 = 42", true, payload),
                new CoverageMethod.Statement(2, "return", false))));
        addPoint(FIRST, 1, "</script><script>alert(1)</script>");
        log.record("statements", payload, "TEST: STATEMENT=" + payload); log.recordCustomMarker(FIRST);
        Path output = temporary.newFile("report.html").toPath();
        new HtmlReportWriter().write(apk, log, "instrumented.apk", "TEST", output.toString(), false);
        String html = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertFalse(html.contains("</script><script>alert(1)</script>"));
        Matcher match = Pattern.compile("<script[^>]*id=\"report-data\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertTrue(match.find()); JsonObject data = JsonParser.parseString(match.group(1)).getAsJsonObject();
        JsonArray rows = data.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("methods")
                .get(0).getAsJsonObject().getAsJsonArray("statements");
        assertEquals("covered", rows.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("unmeasured", rows.get(1).getAsJsonObject().get("status").getAsString());
        assertEquals(FIRST, data.getAsJsonArray("customPoints").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, data.getAsJsonArray("customPoints").get(0).getAsJsonObject().get("index").getAsInt());
    }

    @SuppressWarnings("unchecked") private void addPoint(String id, int line, String message) throws Exception {
        Field field = SummaryBuilder.class.getDeclaredField("customPoints"); field.setAccessible(true);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id); point.put("method", METHOD); point.put("index", line);
        point.put("statement", line == 1 ? "$i2 = 42" : "return"); point.put("message", message);
        ((List<Map<String, Object>>) field.get(apk)).add(point);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> points(Map<String, Object> report) {
        return (List<Map<String, Object>>) ((Map<String, Object>) report.get("summary")).get("points");
    }
}
