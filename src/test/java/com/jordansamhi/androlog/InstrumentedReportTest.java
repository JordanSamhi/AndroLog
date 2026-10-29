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

    @Test public void variableSamplesUseTheirPointIdAndKeepOnlyTheLatestDistinctValues() throws Exception {
        addPoint(FIRST, 1, "score={i3}"); addPoint(SECOND, 2, "score={i3}");
        for (Map<String, Object> point : apk.getCustomPoints()) point.put("dynamic", true);
        Path input = temporary.newFile("values.log").toPath();
        Files.write(input, Arrays.asList(
                "TEST: CUSTOM_VALUE=" + FIRST + "|score=1",
                "TEST: CUSTOM_VALUE=" + SECOND + "|score=99",
                "TEST: CUSTOM_POINT=" + FIRST,
                "TEST: CUSTOM_VALUE=" + FIRST + "|score=2",
                "OTHER: CUSTOM_VALUE=" + FIRST + "|score=wrong",
                "TEST: CUSTOM_VALUE=bad|score=invalid",
                "TEST: CUSTOM_VALUE=" + FIRST + "|score=3",
                "TEST: CUSTOM_VALUE=" + FIRST + "|score=4",
                "TEST: CUSTOM_VALUE=" + FIRST + "|score=2",
                "TEST: CUSTOM_POINT=" + FIRST), StandardCharsets.UTF_8);
        new LogParser("TEST", apk).parseLogs(input.toString());
        List<Map<String, Object>> points = points(CustomPointReport.build(apk, log));
        assertEquals(Arrays.asList("score=3", "score=4", "score=2"), points.get(0).get("samples"));
        assertEquals(Collections.singletonList("score=99"), points.get(1).get("samples"));
        assertEquals(5, points.get(0).get("count")); assertEquals("covered", points.get(0).get("status"));
        assertEquals("covered", points.get(1).get("status")); assertEquals(1, points.get(1).get("count"));
        assertEquals(6, log.getCustomMessageTotal()); assertTrue(log.getSummary().isEmpty());
        assertFalse(log.getCustomMessages().containsKey("score=wrong"));
    }

    @Test public void emptyDynamicRunsKeepTheirTemplatesAndSampleStorageStaysBounded() throws Exception {
        addPoint(FIRST, 1, "score={i3}"); apk.getCustomPoints().get(0).put("dynamic", true);
        Map<String, Object> point = points(CustomPointReport.build(apk, log)).get(0);
        assertEquals("score={i3}", point.get("message"));
        assertEquals(Collections.emptyList(), point.get("samples")); assertEquals("uncovered", point.get("status"));
        for (int i = 0; i < 1100; i++) { log.recordCustomValueMessage("score=" + i); log.recordCustomValue(FIRST, "score=" + i); }
        assertEquals(1000, log.getCustomMessages().size()); assertEquals(1100, log.getCustomMessageTotal());
        assertEquals(100, log.getOmittedCustomMessages()); assertEquals(3, log.getCustomValues(FIRST).size());
    }

    @Test public void dynamicValueAloneProvesCoverageAndPairedEventsCountOnlyOnce() throws Exception {
        addPoint(FIRST, 1, "value={r1}"); addPoint(SECOND, 2, "value={r1}");
        for (Map<String, Object> point : apk.getCustomPoints()) point.put("dynamic", true);
        String unicode = "value=a\u0085b\u2028c\u2029d";
        String foreign = "cccccccccccccccccccccccccccccccc";
        Path input = temporary.newFile("partial.log").toPath();
        Files.write(input, Arrays.asList("TEST: CUSTOM_VALUE=" + FIRST + "|" + unicode,
                "TEST: CUSTOM_VALUE=" + SECOND + "|value=42", "TEST: CUSTOM_POINT=" + SECOND,
                "TEST: CUSTOM_VALUE=" + foreign + "|value=foreign"), StandardCharsets.UTF_8);
        new LogParser("TEST", apk).parseLogs(input.toString());
        List<Map<String, Object>> points = points(CustomPointReport.build(apk, log));
        for (Map<String, Object> point : points) { assertEquals("covered", point.get("status")); assertEquals(1, point.get("count")); }
        assertEquals(Collections.singletonList(unicode), points.get(0).get("samples"));
        assertEquals(0, log.getCustomValueCount(foreign)); assertTrue(log.getCustomValues(foreign).isEmpty());
    }

    @Test public void cappedVariableSummariesDoNotLoseLegacyLiteralCoverage() throws Exception {
        addPoint(null, 1, "legacy literal");
        for (int i = 0; i < 1100; i++) log.recordCustomValueMessage("score=" + i);
        log.recordCustomMessage("legacy literal");
        Map<String, Object> point = points(CustomPointReport.build(apk, log)).get(0);
        assertEquals("covered", point.get("status")); assertEquals(1, point.get("count"));
        assertEquals(1101, log.getCustomMessageTotal()); assertEquals(100, log.getOmittedCustomMessages());
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
