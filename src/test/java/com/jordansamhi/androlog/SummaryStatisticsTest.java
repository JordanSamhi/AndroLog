package com.jordansamhi.androlog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SummaryStatisticsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SummaryBuilder baseline;
    private SummaryLogBuilder observations;
    private final SummaryStatistics statistics = new SummaryStatistics();
    private Locale originalLocale;

    @Before
    public void setUp() throws Exception {
        CoverageTestSupport.resetBuilders();
        baseline = SummaryBuilder.v();
        observations = SummaryLogBuilder.v();
        originalLocale = Locale.getDefault();
    }

    @After
    public void tearDown() throws Exception {
        Locale.setDefault(originalLocale);
        CoverageTestSupport.resetBuilders();
    }

    @Test
    public void aggregateTableUsesStableOrderHumanLabelsAndNeutralZeroTotals() throws Exception {
        Locale.setDefault(Locale.FRANCE);
        baseline.getSummary().put("services", 0);
        baseline.getSummary().put("broadcast-receivers", 2);
        baseline.getSummary().put("methods", 4);
        baseline.getSummary().put("statements", 1000);
        observations.getSummary().put("methods", 1);
        observations.getSummary().put("statements", 100);

        String output = capture(() -> statistics.compareSummaries(baseline, observations));

        assertTrue(output.matches("(?s).*Category\\s+Covered\\s+Total\\s+Coverage.*"));
        assertTrue(output.matches("(?s).*Statements\\s+100\\s+1,000\\s+10\\.0%.*"));
        assertTrue(output.matches("(?s).*Methods\\s+1\\s+4\\s+25\\.0%.*"));
        assertTrue(output.matches("(?s).*Services\\s+0\\s+0\\s+n/a.*"));
        assertTrue(output.contains("Broadcast receivers"));
        assertTrue(output.indexOf("Statements") < output.indexOf("Methods"));
        assertTrue(output.indexOf("Methods") < output.indexOf("Services"));
        assertTrue(output.indexOf("Services") < output.indexOf("Broadcast receivers"));
        assertFalse(output.contains("NaN"));
        assertFalse(output.contains("Infinity"));
        String[] rows = output.substring(output.indexOf("Category")).trim().split("\\R");
        for (String row : rows) {
            assertEquals("Every table row must have aligned columns", rows[0].length(), row.length());
        }
    }

    @Test
    public void minuteTableAndJsonKeepOrderedCumulativeDistinctCounts() throws Exception {
        baseline.getSummary().put("methods", 4);
        observations.record("methods", "second", "10-22 12:35:01.123 TAG: METHOD=second");
        observations.record("methods", "first", "10-22 12:34:01.123 TAG: METHOD=first");
        observations.record("methods", "first", "10-22 12:36:01.123 TAG: METHOD=first");
        observations.record("methods", "untimed", "TAG: METHOD=untimed");

        String output = capture(() -> statistics.compareSummariesPerMinute(baseline, observations));

        assertEquals(1, occurrences(output, "Coverage summary"));
        assertTrue(output.matches("(?s).*Methods\\s+3\\s+4\\s+75\\.0%.*"));
        assertTrue(output.matches("(?s).*10-22 12:34\\s+1 \\(25\\.0%\\).*"));
        assertTrue(output.matches("(?s).*10-22 12:35\\s+2 \\(50\\.0%\\).*"));
        assertTrue(output.matches("(?s).*10-22 12:36\\s+2 \\(50\\.0%\\).*"));
        assertTrue(output.indexOf("10-22 12:34") < output.indexOf("10-22 12:35"));
        assertTrue(output.contains("overall totals only"));

        Path path = temporary.getRoot().toPath().resolve("minutes.json");
        assertEquals("", capture(() -> statistics.compareSummariesPerMinuteToJson(baseline, observations, path.toString())));
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        JsonObject report = JsonParser.parseString(json).getAsJsonObject();
        JsonObject minutes = report.getAsJsonObject("perMinuteSummaries");
        assertEquals(3, report.getAsJsonObject("coverageSummary").getAsJsonObject("methods").get("covered").getAsInt());
        assertEquals(1, minutes.getAsJsonObject("10-22 12:34").getAsJsonObject("methods").get("cumulative").getAsInt());
        JsonObject last = minutes.getAsJsonObject("10-22 12:36").getAsJsonObject("methods");
        assertEquals(2, last.get("cumulative").getAsInt());
        assertEquals(4, last.get("overall").getAsInt());
        assertEquals("50.0%", last.get("percent").getAsString());
        assertFalse(last.has("covered"));
        assertTrue(json.indexOf("10-22 12:34") < json.indexOf("10-22 12:35"));
    }

    @Test
    public void missingTimestampsAreExplainedWithoutInventingMinuteRows() throws Exception {
        baseline.getSummary().put("methods", 1);
        observations.record("methods", "only", "TAG: METHOD=only");

        String output = capture(() -> statistics.compareSummariesPerMinute(baseline, observations));

        assertTrue(output.contains("No timestamped observations are available."));
        assertTrue(output.matches("(?s).*Methods\\s+1\\s+1\\s+100\\.0%.*"));
        assertFalse(output.contains("Log minute"));

        Path path = temporary.getRoot().toPath().resolve("untimed.json");
        statistics.compareSummariesPerMinuteToJson(baseline, observations, path.toString());
        JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(0, json.getAsJsonObject("perMinuteSummaries").size());
    }

    @Test
    public void jsonKeepsItsSchemaAndDecimalFormatAcrossLocalesAndCreatesParents() throws Exception {
        Locale.setDefault(Locale.FRANCE);
        baseline.getSummary().put("methods", 3);
        baseline.getSummary().put("services", 0);
        baseline.getSummary().put("é_日本", 1);
        observations.getSummary().put("methods", 1);
        Path path = temporary.getRoot().toPath().resolve("new/nested/report.json");

        assertEquals("", capture(() -> statistics.compareSummariesToJson(baseline, observations, path.toString())));

        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(1, json.size());
        JsonObject summary = json.getAsJsonObject("coverageSummary");
        JsonObject methods = summary.getAsJsonObject("methods");
        assertEquals(3, methods.size());
        assertEquals(1, methods.get("covered").getAsInt());
        assertEquals(3, methods.get("total").getAsInt());
        assertEquals("33.3%", methods.get("percent").getAsString());
        assertEquals("0.0%", summary.getAsJsonObject("services").get("percent").getAsString());
        assertTrue(summary.has("é_日本"));
        assertTrue(content.contains("é_日本"));
    }

    @Test
    public void bothJsonWritersPropagateWriteFailures() throws Exception {
        Path directory = temporary.newFolder("cannot-write-over-directory").toPath();

        UncheckedIOException aggregate = assertThrows(UncheckedIOException.class,
                () -> statistics.compareSummariesToJson(baseline, observations, directory.toString()));
        UncheckedIOException minutes = assertThrows(UncheckedIOException.class,
                () -> statistics.compareSummariesPerMinuteToJson(baseline, observations, directory.toString()));

        assertTrue(aggregate.getMessage().contains(directory.toString()));
        assertTrue(minutes.getMessage().contains(directory.toString()));
        assertNotNull(aggregate.getCause());
        assertNotNull(minutes.getCause());
    }

    @Test
    public void graphOnlyRunsPrintAndExportConfirmedDispatch() throws Exception {
        baseline.setCollectCallGraph(true);
        baseline.setLauncherActivities(java.util.Collections.singletonList("example.Caller"));
        CoverageTestSupport.addCallSite(baseline,
                new CallSite(CallGraphTest.CALLER, 4, CallGraphTest.DECLARED, "virtual"), CallGraphTest.CHILD);
        observations.getCallGraph().accept(CallGraphTest.record(1, "O", 4, CallGraphTest.DECLARED,
                CallGraphTest.CHILD, "virtual"), baseline.getCallSites(), baseline.getGraphMethods());

        String output = capture(() -> statistics.compareSummaries(baseline, observations));
        assertTrue(output.contains("Dynamic call graph"));
        assertTrue(output.contains("1 confirmed"));
        Path path = temporary.getRoot().toPath().resolve("graph.json");
        statistics.compareSummariesToJson(baseline, observations, path.toString());
        JsonObject data = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(0, data.getAsJsonObject("coverageSummary").size());
        assertEquals(1, data.getAsJsonObject("callGraph").getAsJsonObject("stats").get("observedEdges").getAsInt());
        assertEquals("example.Caller", data.getAsJsonObject("callGraph").getAsJsonArray("launcherActivities").get(0).getAsString());
        statistics.compareSummariesPerMinuteToJson(baseline, observations, path.toString());
        JsonObject perMinute = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("example.Caller", perMinute.getAsJsonObject("callGraph").getAsJsonArray("launcherActivities").get(0).getAsString());
    }

    private String capture(Runnable action) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8.name())) {
            System.setOut(stream);
            action.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private int occurrences(String text, String search) {
        return (text.length() - text.replace(search, "").length()) / search.length();
    }
}
